// Lockstep netplay over UDP. See Netplay.h for the big picture.
//
// Topology: a star. Player 0 is the host. Clients only talk to the host.
//   client -> host : INPUT  (my inputs for the next few frames, redundantly, + state hash)
//   host -> client : FRAMES (the complete input set of the last few frames, redundantly)
// UDP packets can be lost or reordered; the redundancy plus periodic re-sends make that harmless,
// and the game simply waits (a stall) until the missing frame arrives.
#include "Netplay.h"

#if defined(__ANDROID__) || defined(__linux__) || defined(__APPLE__)
#define NETPLAY_POSIX 1
#endif

#ifdef NETPLAY_POSIX

#include <arpa/inet.h>
#include <netdb.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

constexpr uint32_t kMagic = 0x4D4B3634; // "MK64"
constexpr uint8_t kVersion = 1;
enum : uint8_t { kHello = 1, kStart = 2, kInput = 3, kFrames = 4 };

constexpr int kRing = 256;          // frames kept in memory (must be a power of two, > delay + window)
constexpr int kMaxFramesPerPacket = 24;
constexpr int kResendMs = 15;
constexpr int kStallAbortMs = 30000;
constexpr int kJoinTimeoutMs = 180000;
constexpr size_t kInputBytes = 6;
constexpr size_t kHeaderBytes = 7;

struct Slot {
    int64_t tag = -1;
    uint32_t have = 0;
    NetplayInput in[NETPLAY_MAX_PLAYERS];
};

struct HashSlot {
    int64_t tag = -1;
    uint32_t value = 0;
};

bool sActive = false;
bool sHost = false;
bool sDesynced = false;
bool sFullScreen = true;
int sSock = -1;
int sPort = 27015;
int sPlayers = 2;
int sIndex = 0;
int sDelay = 3;
uint32_t sFullMask = 0;
int64_t sFrame = 0;

sockaddr_in sHostAddr;                       // client: where the host is
sockaddr_in sPeerAddr[NETPLAY_MAX_PLAYERS];  // host: where each client is
bool sPeerKnown[NETPLAY_MAX_PLAYERS];
int64_t sPeerAck[NETPLAY_MAX_PLAYERS];       // host: next frame each client says it needs

Slot sSlots[kRing];
HashSlot sMyHash[kRing];
HashSlot sPeerHash[NETPLAY_MAX_PLAYERS][kRing];

#ifdef __ANDROID__
constexpr const char* kLogFile = "/sdcard/Android/data/dev.mdhigino.mk64family/files/netplay.log";
#endif

void Log(const char* fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "Netplay", "%s", buf);
    // Also keep our own log file: the system log can come back empty on some phones.
    if (FILE* lf = fopen(kLogFile, "a")) {
        timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        fprintf(lf, "[%ld] %s\n", static_cast<long>(ts.tv_sec), buf);
        fclose(lf);
    }
#else
    fprintf(stderr, "[Netplay] %s\n", buf);
#endif
}

int64_t NowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// ---- tiny little-endian packet writer/reader ----
struct Writer {
    uint8_t buf[1500];
    size_t n = 0;
    void u8(uint8_t v) { buf[n++] = v; }
    void u16(uint16_t v) { u8(v & 0xFF); u8(v >> 8); }
    void u32(uint32_t v) { u16(v & 0xFFFF); u16(v >> 16); }
    void input(const NetplayInput& i) {
        u16(i.button);
        u8(static_cast<uint8_t>(i.stick_x));
        u8(static_cast<uint8_t>(i.stick_y));
        u8(static_cast<uint8_t>(i.right_stick_x));
        u8(static_cast<uint8_t>(i.right_stick_y));
    }
    void header(uint8_t type, uint8_t player) {
        u32(kMagic);
        u8(kVersion);
        u8(type);
        u8(player);
    }
};

struct Reader {
    const uint8_t* p;
    size_t len;
    size_t pos = 0;
    bool ok = true;
    uint8_t u8() {
        if (pos + 1 > len) { ok = false; return 0; }
        return p[pos++];
    }
    uint16_t u16() { uint16_t a = u8(); return a | (static_cast<uint16_t>(u8()) << 8); }
    uint32_t u32() { uint32_t a = u16(); return a | (static_cast<uint32_t>(u16()) << 16); }
    NetplayInput input() {
        NetplayInput i;
        i.button = u16();
        i.stick_x = static_cast<int8_t>(u8());
        i.stick_y = static_cast<int8_t>(u8());
        i.right_stick_x = static_cast<int8_t>(u8());
        i.right_stick_y = static_cast<int8_t>(u8());
        return i;
    }
};

Slot& SlotFor(int64_t frame) {
    Slot& s = sSlots[frame & (kRing - 1)];
    if (s.tag != frame) {
        s.tag = frame;
        s.have = 0;
        memset(s.in, 0, sizeof(s.in));
    }
    return s;
}

void SetInput(int player, int64_t frame, const NetplayInput& in) {
    if (frame < sFrame) {
        return; // already consumed
    }
    Slot& s = SlotFor(frame);
    if (s.have & (1u << player)) {
        return; // first value wins: inputs are immutable once known
    }
    s.in[player] = in;
    s.have |= 1u << player;
}

bool IsComplete(int64_t frame) {
    const Slot& s = sSlots[frame & (kRing - 1)];
    return s.tag == frame && s.have == sFullMask;
}

// ---- optional internet relay (a tiny UDP server on a VPS, see tools/relay/) ----
// Phones behind routers / mobile networks cannot reach each other directly, so in relay mode every
// packet is wrapped in a small envelope and passed through the relay, which forwards it by room code.
// The rest of the code does not notice: peers are represented by made-up addresses (10.255.0.<id>).
constexpr uint32_t kRelayMagic = 0x4D4B524C; // "MKRL"
enum : uint8_t { kRlHost = 1, kRlUp = 2, kRlDown = 3, kRlFrom = 4, kRlFromHost = 5 };
constexpr size_t kRelayHeader = 10;

bool sRelay = false;
int64_t sLastRelayReg = 0;
uint32_t sRoom = 0;
sockaddr_in sRelayAddr;

sockaddr_in FakeAddr(int id) {
    sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(0x0AFF0000u | static_cast<uint32_t>(id));
    a.sin_port = htons(1);
    return a;
}

void RawSend(const uint8_t* data, size_t n, const sockaddr_in& to) {
#ifdef NETPLAY_TEST
    // Test builds can simulate a lossy network: NETPLAY_LOSS=30 drops 30% of outgoing packets.
    static int loss = getenv("NETPLAY_LOSS") ? atoi(getenv("NETPLAY_LOSS")) : 0;
    if (loss > 0 && (rand() % 100) < loss) {
        return;
    }
#endif
    sendto(sSock, data, n, 0, reinterpret_cast<const sockaddr*>(&to), sizeof(to));
}

void RelayEnvelope(uint8_t* out, uint8_t type) {
    out[0] = kRelayMagic & 0xFF;
    out[1] = (kRelayMagic >> 8) & 0xFF;
    out[2] = (kRelayMagic >> 16) & 0xFF;
    out[3] = (kRelayMagic >> 24) & 0xFF;
    out[4] = 1;
    out[5] = type;
    out[6] = sRoom & 0xFF;
    out[7] = (sRoom >> 8) & 0xFF;
    out[8] = (sRoom >> 16) & 0xFF;
    out[9] = (sRoom >> 24) & 0xFF;
}

void RelayRegisterHost() {
    uint8_t pkt[kRelayHeader];
    RelayEnvelope(pkt, kRlHost);
    RawSend(pkt, sizeof(pkt), sRelayAddr);
}

void SendTo(const Writer& w, const sockaddr_in& to) {
    if (!sRelay) {
        RawSend(w.buf, w.n, to);
        return;
    }
    uint8_t pkt[1600];
    if (w.n + kRelayHeader + 1 > sizeof(pkt)) {
        return;
    }
    size_t n = kRelayHeader;
    if (sHost) {
        RelayEnvelope(pkt, kRlDown);
        pkt[n++] = static_cast<uint8_t>(ntohl(to.sin_addr.s_addr) & 0xFF); // which client
    } else {
        RelayEnvelope(pkt, kRlUp);
    }
    memcpy(pkt + n, w.buf, w.n);
    RawSend(pkt, n + w.n, sRelayAddr);
}

// Receives one game packet. In relay mode the envelope is removed and `from` is the made-up address.
// Returns the payload length, 0 if the datagram was not a game packet, -1 if nothing was waiting.
ssize_t RecvOne(uint8_t* buf, size_t cap, sockaddr_in* from, int flags) {
    uint8_t raw[1700];
    sockaddr_in src;
    socklen_t slen = sizeof(src);
    ssize_t n = recvfrom(sSock, raw, sizeof(raw), flags, reinterpret_cast<sockaddr*>(&src), &slen);
    if (n < 0) {
        return -1;
    }
    if (!sRelay) {
        if (static_cast<size_t>(n) > cap) {
            return 0;
        }
        memcpy(buf, raw, n);
        *from = src;
        return n;
    }
    if (src.sin_addr.s_addr != sRelayAddr.sin_addr.s_addr || src.sin_port != sRelayAddr.sin_port ||
        n < static_cast<ssize_t>(kRelayHeader + 1)) {
        return 0;
    }
    uint32_t magic = raw[0] | (raw[1] << 8) | (raw[2] << 16) | (static_cast<uint32_t>(raw[3]) << 24);
    if (magic != kRelayMagic) {
        return 0;
    }
    size_t off = kRelayHeader;
    uint8_t type = raw[5];
    if (type == kRlFrom && sHost) {
        *from = FakeAddr(raw[off++]);
    } else if (type == kRlFromHost && !sHost) {
        *from = FakeAddr(0);
    } else {
        return 0;
    }
    size_t len = static_cast<size_t>(n) - off;
    if (len > cap) {
        return 0;
    }
    memcpy(buf, raw + off, len);
    return static_cast<ssize_t>(len);
}

bool SameAddr(const sockaddr_in& a, const sockaddr_in& b) {
    return a.sin_addr.s_addr == b.sin_addr.s_addr && a.sin_port == b.sin_port;
}

void CheckHash(int player, int64_t frame) {
    const HashSlot& mine = sMyHash[frame & (kRing - 1)];
    const HashSlot& theirs = sPeerHash[player][frame & (kRing - 1)];
    if (mine.tag == frame && theirs.tag == frame && mine.value != theirs.value && !sDesynced) {
        sDesynced = true;
        Log("DESYNC at frame %lld: host=%08x player%d=%08x", static_cast<long long>(frame), mine.value, player,
            theirs.value);
    }
}

void SendStart(const sockaddr_in& to, int index) {
    Writer w;
    w.header(kStart, 0);
    w.u8(static_cast<uint8_t>(index));
    w.u8(static_cast<uint8_t>(sPlayers));
    w.u8(static_cast<uint8_t>(sDelay));
    SendTo(w, to);
}

// ---- host ----
int HostFindOrAdd(const sockaddr_in& from) {
    for (int i = 1; i < sPlayers; i++) {
        if (sPeerKnown[i] && SameAddr(sPeerAddr[i], from)) {
            return i;
        }
    }
    for (int i = 1; i < sPlayers; i++) {
        if (!sPeerKnown[i]) {
            sPeerKnown[i] = true;
            sPeerAddr[i] = from;
            char ip[32];
            inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
            Log("player %d joined from %s:%d", i, ip, ntohs(from.sin_port));
            return i;
        }
    }
    return -1;
}

void HostBroadcast(bool force) {
    // Consumed frames stay in the ring for a while: a client may still need them re-sent.
    int64_t base = sFrame - 12;
    if (base < 0) {
        base = 0;
    }
    int64_t hi = base - 1;
    while (hi + 1 < sFrame + kRing / 4 && IsComplete(hi + 1)) {
        hi++;
    }
    if (hi < base) {
        return;
    }
    for (int p = 1; p < sPlayers; p++) {
        if (!sPeerKnown[p]) {
            continue;
        }
        int64_t lo = sPeerAck[p];
        if (lo < base) {
            lo = base;
        }
        if (!force && lo > hi) {
            continue; // this client already has everything
        }
        if (lo > hi) {
            lo = hi;
        }
        if (hi - lo + 1 > kMaxFramesPerPacket) {
            lo = hi - kMaxFramesPerPacket + 1;
        }
        Writer w;
        w.header(kFrames, 0);
        w.u32(static_cast<uint32_t>(lo));
        w.u8(static_cast<uint8_t>(hi - lo + 1));
        w.u8(static_cast<uint8_t>(sPlayers));
        for (int64_t f = lo; f <= hi; f++) {
            const Slot& s = sSlots[f & (kRing - 1)];
            for (int pl = 0; pl < sPlayers; pl++) {
                w.input(s.tag == f ? s.in[pl] : NetplayInput{});
            }
        }
        SendTo(w, sPeerAddr[p]);
    }
}

// ---- client ----
void ClientSendInputs() {
    int64_t lo = sFrame;
    // Only send inputs that really exist: a frame I haven't played yet must not be sent as "nothing".
    int64_t hi = lo - 1;
    while (hi < sFrame + sDelay) {
        const Slot& sl = sSlots[(hi + 1) & (kRing - 1)];
        if (sl.tag != hi + 1 || !(sl.have & (1u << sIndex))) {
            break;
        }
        hi++;
    }
    Writer w;
    w.header(kInput, static_cast<uint8_t>(sIndex));
    w.u32(static_cast<uint32_t>(sFrame));            // ack: next frame I need
    const HashSlot& h = sMyHash[sFrame & (kRing - 1)];
    w.u32(h.tag == sFrame ? static_cast<uint32_t>(sFrame) : 0xFFFFFFFFu);
    w.u32(h.tag == sFrame ? h.value : 0);
    w.u32(static_cast<uint32_t>(lo));
    w.u8(static_cast<uint8_t>(hi - lo + 1));
    for (int64_t f = lo; f <= hi; f++) {
        w.input(sSlots[f & (kRing - 1)].in[sIndex]);
    }
    SendTo(w, sHostAddr);
}

// ---- receive path (both roles) ----
void HandlePacket(const uint8_t* data, size_t len, const sockaddr_in& from) {
    Reader r{data, len};
    if (r.u32() != kMagic || r.u8() != kVersion) {
        return;
    }
    uint8_t type = r.u8();
    uint8_t player = r.u8();
    if (!r.ok) {
        return;
    }

    if (sHost) {
        if (type == kHello) {
            int idx = HostFindOrAdd(from);
            if (idx > 0) {
                SendStart(from, idx);
            }
        } else if (type == kInput && player > 0 && player < sPlayers && sPeerKnown[player] &&
                   SameAddr(sPeerAddr[player], from)) {
            uint32_t ack = r.u32();
            uint32_t hashFrame = r.u32();
            uint32_t hash = r.u32();
            uint32_t first = r.u32();
            uint8_t count = r.u8();
            if (!r.ok || count > 64) {
                return;
            }
            if (ack > sPeerAck[player]) {
                sPeerAck[player] = ack;
            }
            for (int i = 0; i < count; i++) {
                NetplayInput in = r.input();
                if (!r.ok) {
                    return;
                }
                SetInput(player, static_cast<int64_t>(first) + i, in);
            }
            if (hashFrame != 0xFFFFFFFFu) {
                HashSlot& hs = sPeerHash[player][hashFrame & (kRing - 1)];
                hs.tag = hashFrame;
                hs.value = hash;
                CheckHash(player, hashFrame);
            }
        }
    } else if (type == kFrames) {
        uint32_t first = r.u32();
        uint8_t count = r.u8();
        uint8_t n = r.u8();
        if (!r.ok || n != sPlayers || count > kMaxFramesPerPacket) {
            return;
        }
        for (int i = 0; i < count; i++) {
            for (int p = 0; p < n; p++) {
                NetplayInput in = r.input();
                if (!r.ok) {
                    return;
                }
                SetInput(p, static_cast<int64_t>(first) + i, in);
            }
        }
    }
}

void Pump(int waitMs) {
    pollfd pfd;
    pfd.fd = sSock;
    pfd.events = POLLIN;
    int ret = poll(&pfd, 1, waitMs);
    while (ret > 0) {
        uint8_t buf[1600];
        sockaddr_in from;
        ssize_t n = RecvOne(buf, sizeof(buf), &from, MSG_DONTWAIT);
        if (n < 0) {
            break;
        }
        if (n > 0) {
            HandlePacket(buf, static_cast<size_t>(n), from);
        }
        ret = poll(&pfd, 1, 0);
    }
}

// ---- config ----
struct Config {
    bool on = false;
    bool host = false;
    int players = 2;
    int port = 27015;
    int delay = 3;
    bool fullscreen = true;
    char relay[96] = {0};
    uint32_t room = 0;
    char hostIp[64] = {0};
};

bool ReadConfig(const char* path, Config& c) {
    FILE* f = fopen(path, "r");
    if (!f) {
        return false;
    }
    char line[160];
    while (fgets(line, sizeof(line), f)) {
        char* eq = strchr(line, '=');
        if (!eq) {
            continue;
        }
        *eq = 0;
        char* val = eq + 1;
        val[strcspn(val, "\r\n ")] = 0;
        if (!strcmp(line, "mode")) {
            c.host = !strcmp(val, "host");
            c.on = !strcmp(val, "host") || !strcmp(val, "client");
        } else if (!strcmp(line, "players")) {
            c.players = atoi(val);
        } else if (!strcmp(line, "port")) {
            c.port = atoi(val);
        } else if (!strcmp(line, "relay")) {
            strncpy(c.relay, val, sizeof(c.relay) - 1);
        } else if (!strcmp(line, "room")) {
            c.room = static_cast<uint32_t>(strtoul(val, nullptr, 10));
        } else if (!strcmp(line, "fullscreen")) {
            c.fullscreen = atoi(val) != 0;
        } else if (!strcmp(line, "delay")) {
            c.delay = atoi(val);
        } else if (!strcmp(line, "host")) {
            strncpy(c.hostIp, val, sizeof(c.hostIp) - 1);
        }
    }
    fclose(f);
    return true;
}

void CloseSocket() {
    if (sSock >= 0) {
        close(sSock);
        sSock = -1;
    }
}

} // namespace

extern "C" {

void Netplay_Log(const char* fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Log("%s", buf);
}

int Netplay_Start(const char* cfgPath) {
    if (sActive) {
        return 1;
    }
#ifdef __ANDROID__
    if (FILE* lf = fopen(kLogFile, "w")) { // fresh log for this run
        fclose(lf);
    }
#endif
    Config c;
#ifdef __ANDROID__
    const char* defaultPath = "/sdcard/Android/data/dev.mdhigino.mk64family/files/netplay.cfg";
#else
    const char* defaultPath = "netplay.cfg";
#endif
    bool cfgRead = ReadConfig(cfgPath ? cfgPath : defaultPath, c);
    Log("Netplay_Start: config %s, mode=%s, relay='%s', room=%u, players=%d, delay=%d", cfgRead ? "read" : "NOT FOUND",
        !c.on ? "off" : (c.host ? "host" : "client"), c.relay, c.room, c.players, c.delay);
    if (!cfgRead || !c.on) {
        return 0;
    }
    if (c.players < 2) c.players = 2;
    if (c.players > NETPLAY_MAX_PLAYERS) c.players = NETPLAY_MAX_PLAYERS;
    if (c.delay < 1) c.delay = 1;
    if (c.delay > 10) c.delay = 10;

    sHost = c.host;
    sRelay = false;
    if (c.relay[0]) {
        // "address:port" of the relay server; the address may be a name.
        char hostPart[96];
        strncpy(hostPart, c.relay, sizeof(hostPart) - 1);
        hostPart[sizeof(hostPart) - 1] = 0;
        int relayPort = 27016;
        char* colon = strrchr(hostPart, ':');
        if (colon) {
            *colon = 0;
            relayPort = atoi(colon + 1);
        }
        addrinfo hints;
        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_DGRAM;
        addrinfo* res = nullptr;
        if (getaddrinfo(hostPart, nullptr, &hints, &res) != 0 || !res) {
            Log("relay: cannot resolve '%s'", hostPart);
            return 0;
        }
        memcpy(&sRelayAddr, res->ai_addr, sizeof(sRelayAddr));
        sRelayAddr.sin_port = htons(relayPort);
        freeaddrinfo(res);
        sRoom = c.room;
        sRelay = (sRoom != 0);
        if (sRelay) {
            Log("online mode: relay %s port %d, room %u", hostPart, relayPort, sRoom);
        }
    }
    sFullScreen = c.fullscreen;
    sPort = c.port;
    sPlayers = c.players;
    sDelay = c.delay;
    sFullMask = (sPlayers >= 32) ? 0xFFFFFFFFu : ((1u << sPlayers) - 1);
    sFrame = 0;
    sDesynced = false;
    for (int i = 0; i < kRing; i++) {
        sSlots[i] = Slot{};
        sMyHash[i] = HashSlot{};
    }
    for (int p = 0; p < NETPLAY_MAX_PLAYERS; p++) {
        sPeerKnown[p] = false;
        sPeerAck[p] = 0;
        for (int i = 0; i < kRing; i++) {
            sPeerHash[p][i] = HashSlot{};
        }
    }

    sSock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sSock < 0) {
        Log("socket() failed: %s", strerror(errno));
        return 0;
    }
    int one = 1;
    setsockopt(sSock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    sockaddr_in bindAddr;
    memset(&bindAddr, 0, sizeof(bindAddr));
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    bindAddr.sin_port = htons((sHost && !sRelay) ? sPort : 0); // relay mode: any free port
    if (bind(sSock, reinterpret_cast<sockaddr*>(&bindAddr), sizeof(bindAddr)) < 0) {
        Log("bind() failed: %s", strerror(errno));
        CloseSocket();
        return 0;
    }

    int64_t t0 = NowMs();
    if (sHost) {
        sIndex = 0;
        Log("host: waiting for %d player(s)%s", sPlayers - 1, sRelay ? " through the relay" : "");
        int64_t lastReg = 0;
        for (;;) {
            if (sRelay && NowMs() - lastReg >= 1000) {
                RelayRegisterHost();
                lastReg = NowMs();
            }
            Pump(50);
            int joined = 0;
            for (int i = 1; i < sPlayers; i++) {
                joined += sPeerKnown[i] ? 1 : 0;
            }
            if (joined == sPlayers - 1) {
                break;
            }
            if (NowMs() - t0 > kJoinTimeoutMs) {
                Log("host: gave up waiting for players");
                CloseSocket();
                return 0;
            }
        }
        for (int rep = 0; rep < 3; rep++) {
            for (int i = 1; i < sPlayers; i++) {
                SendStart(sPeerAddr[i], i);
            }
        }
    } else {
        memset(&sHostAddr, 0, sizeof(sHostAddr));
        if (sRelay) {
            sHostAddr = FakeAddr(0);
        } else {
            sHostAddr.sin_family = AF_INET;
            sHostAddr.sin_port = htons(sPort);
            if (inet_pton(AF_INET, c.hostIp, &sHostAddr.sin_addr) != 1) {
                Log("client: invalid host address '%s'", c.hostIp);
                CloseSocket();
                return 0;
            }
        }
        Log("client: looking for the host%s", sRelay ? " through the relay" : "");
        bool started = false;
        int64_t lastHello = 0;
        while (!started) {
            int64_t now = NowMs();
            if (now - lastHello >= 200) {
                Writer w;
                w.header(kHello, 0);
                SendTo(w, sHostAddr);
                lastHello = now;
            }
            pollfd pfd;
            pfd.fd = sSock;
            pfd.events = POLLIN;
            if (poll(&pfd, 1, 50) > 0) {
                uint8_t buf[256];
                sockaddr_in from;
                ssize_t n = RecvOne(buf, sizeof(buf), &from, 0);
                if (n >= static_cast<ssize_t>(kHeaderBytes) && SameAddr(from, sHostAddr)) {
                    Reader r{buf, static_cast<size_t>(n)};
                    if (r.u32() == kMagic && r.u8() == kVersion && r.u8() == kStart) {
                        r.u8(); // header player
                        sIndex = r.u8();
                        sPlayers = r.u8();
                        sDelay = r.u8();
                        if (r.ok && sIndex > 0 && sIndex < sPlayers && sPlayers <= NETPLAY_MAX_PLAYERS) {
                            sFullMask = (1u << sPlayers) - 1;
                            started = true;
                        }
                    }
                }
            }
            if (NowMs() - t0 > kJoinTimeoutMs) {
                Log("client: host not found");
                CloseSocket();
                return 0;
            }
        }
        Log("client: joined as player %d of %d", sIndex, sPlayers);
    }

    // The first `delay` frames have no network input yet: everybody plays "nothing" for them.
    for (int p = 0; p < sPlayers; p++) {
        for (int f = 0; f < sDelay; f++) {
            SetInput(p, f, NetplayInput{});
        }
    }
    sActive = true;
    Log("session started: %d players, input delay %d frames, I am player %d", sPlayers, sDelay, sIndex);
    return 1;
}

int Netplay_Active(void) { return sActive ? 1 : 0; }
int Netplay_LocalIndex(void) { return sActive ? sIndex : -1; }
int Netplay_PlayerCount(void) { return sActive ? sPlayers : 0; }
int Netplay_Desynced(void) { return sDesynced ? 1 : 0; }
int Netplay_FullScreen(void) { return sFullScreen ? 1 : 0; }

int Netplay_Frame(const NetplayInput* local, uint32_t stateHash, NetplayInput* out) {
    if (!sActive) {
        return 0;
    }
    const int64_t f = sFrame;

    SetInput(sIndex, f + sDelay, *local);
    HashSlot& mh = sMyHash[f & (kRing - 1)];
    mh.tag = f;
    mh.value = stateHash;
    if (sHost) {
        for (int p = 1; p < sPlayers; p++) {
            CheckHash(p, f);
        }
    }

    int64_t waitStart = NowMs();
    int64_t lastSend = 0;
    bool first = true;
    Pump(0);
    while (!IsComplete(f)) {
        int64_t now = NowMs();
        if (sRelay && sHost && now - sLastRelayReg >= 1000) {
            RelayRegisterHost();
            sLastRelayReg = now;
        }
        if (first || now - lastSend >= kResendMs) {
            if (sHost) {
                HostBroadcast(true);
            } else {
                ClientSendInputs();
            }
            lastSend = now;
            first = false;
        }
        if (now - waitStart > kStallAbortMs) {
            Log("stalled for %d s at frame %lld: leaving netplay", kStallAbortMs / 1000, static_cast<long long>(f));
            Netplay_Stop();
            return 0;
        }
        Pump(5);
    }

    const Slot& s = sSlots[f & (kRing - 1)];
    for (int p = 0; p < NETPLAY_MAX_PLAYERS; p++) {
        out[p] = (p < sPlayers) ? s.in[p] : NetplayInput{};
    }

    // Tell everybody what we know right away so the others don't wait for the resend timer.
    sFrame = f + 1;
    if (sHost) {
        HostBroadcast(false);
    } else {
        ClientSendInputs();
    }
    return 1;
}

void Netplay_Stop(void) {
    sActive = false;
    CloseSocket();
}

} // extern "C"

#else // no sockets on this platform

extern "C" {
int Netplay_Start(const char*) { return 0; }
void Netplay_Log(const char*, ...) {}
int Netplay_Active(void) { return 0; }
int Netplay_LocalIndex(void) { return -1; }
int Netplay_PlayerCount(void) { return 0; }
int Netplay_Frame(const NetplayInput*, uint32_t, NetplayInput*) { return 0; }
int Netplay_Desynced(void) { return 0; }
int Netplay_FullScreen(void) { return 0; }
void Netplay_Stop(void) {}
}

#endif
