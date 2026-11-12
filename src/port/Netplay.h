// Local-network (Wi-Fi) multiplayer for family play: deterministic lockstep over UDP.
//
// Every phone runs the whole game. Only the controller inputs travel over the network.
// Each frame, the host collects the input of every player, and once it has all of them it
// sends the complete set to everybody; every phone then feeds the same inputs to the same
// game and therefore sees the same race. Inputs are delayed a few frames so that Wi-Fi
// latency does not stall the game.
//
// The lobby (Java) writes a small text file, netplay.cfg, before the game starts:
//   mode=host | client
//   players=2..8        (host only: how many phones will play)
//   host=192.168.0.10   (client only: the host's address)
//   port=27015
//   delay=3             (input delay in frames, host decides)
//   fullscreen=1       (each phone draws only its own player, full screen)
//   battle=ai          (Battle: empty slots up to 4 karts are driven by the computer)
// No file (or mode=off) means normal single-device play.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NETPLAY_MAX_PLAYERS 8

typedef struct {
    uint16_t button;
    int8_t stick_x;
    int8_t stick_y;
    int8_t right_stick_x;
    int8_t right_stick_y;
} NetplayInput;

// Reads the config file (path may be NULL for "./netplay.cfg"). Returns 1 when netplay is on.
int Netplay_Start(const char* cfgPath);

// Returns 1 while a netplay session is running.
int Netplay_Active(void);

// This phone's player slot (0 = host), or -1.
int Netplay_LocalIndex(void);
int Netplay_PlayerCount(void);

// Call exactly once per game frame, before the game reads its controllers.
//   local     : what this phone's controller is doing right now
//   stateHash : cheap fingerprint of the game state (to detect the games drifting apart)
//   out[]     : receives the inputs all players must use for this frame (NETPLAY_MAX_PLAYERS)
// Blocks (politely) until every player's input for this frame is known.
// Returns 1 on success, 0 if the session was aborted (caller should fall back to local input).
int Netplay_Frame(const NetplayInput* local, uint32_t stateHash, NetplayInput* out);

// 1 when each phone should draw only its own player, full screen (config key fullscreen=1, default).
int Netplay_FullScreen(void);

// 1 when the config asks for computer players in Battle (key battle=ai).
int Netplay_BattleAI(void);

// 1 when a single phone plays Battle against computer players and wants its own screen full size.
int Netplay_SoloFullScreen(void);

// 1 once a state mismatch between phones has been detected.
int Netplay_Desynced(void);

void Netplay_Stop(void);

// Writes a line to the log (logcat on Android).
void Netplay_Log(const char* fmt, ...);

#ifdef __cplusplus
}
#endif
