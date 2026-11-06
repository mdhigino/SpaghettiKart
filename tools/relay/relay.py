#!/usr/bin/env python3
"""Relay UDP do MK64 Familia.

Celulares em redes diferentes nao conseguem se falar direto. Todos mandam os pacotes para este
servidor, que os repassa pelo codigo da sala. O relay NAO roda o jogo e nao entende os pacotes:
so encaminha. Formato do envelope (little-endian):
  magic 'MKRL' (4) | versao=1 (1) | tipo (1) | sala u32 (4) | dados...
  tipo 1 HOST     celular anfitriao -> relay : "estou na sala X" (reenviado a cada 1 s)
  tipo 2 UP       cliente -> relay           : dados para o anfitriao
  tipo 3 DOWN     anfitriao -> relay         : id do cliente (1 byte) + dados
  tipo 4 FROM     relay -> anfitriao         : id do cliente (1 byte) + dados
  tipo 5 FROMHOST relay -> cliente           : dados
"""
import os
import select
import socket
import struct
import time

PORT = int(os.environ.get("RELAY_PORT", "27016"))
ROOM_TTL = 600          # segundos sem trafego -> a sala some
MAX_CLIENTS_PER_ROOM = 7
MAGIC = 0x4D4B524C
HEADER = 10

T_HOST, T_UP, T_DOWN, T_FROM, T_FROMHOST = 1, 2, 3, 4, 5


class Room:
    def __init__(self):
        self.host = None
        self.clients = {}      # addr -> id
        self.by_id = {}        # id -> addr
        self.last = time.time()


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", PORT))
    print(f"relay ouvindo em UDP {PORT}", flush=True)
    rooms = {}
    last_clean = time.time()
    last_stats = time.time()
    forwarded = 0

    while True:
        ready, _, _ = select.select([sock], [], [], 1.0)
        now = time.time()
        if ready:
            for _ in range(200):          # esvazia o que chegou
                try:
                    data, addr = sock.recvfrom(2048)
                except BlockingIOError:
                    break
                except OSError:
                    break
                if len(data) < HEADER + 1 and not (len(data) == HEADER and data[5] == T_HOST):
                    continue
                magic, ver, typ, room_id = struct.unpack_from("<IBBI", data, 0)
                if magic != MAGIC or ver != 1 or room_id == 0:
                    continue
                room = rooms.get(room_id)

                if typ == T_HOST:
                    if room is None:
                        room = rooms[room_id] = Room()
                        print(f"sala {room_id} criada por {addr[0]}", flush=True)
                    if room.host is not None and room.host != addr:
                        continue            # outra pessoa tentando usar a sala: ignora
                    room.host = addr
                    room.last = now
                elif typ == T_UP:
                    if room is None or room.host is None:
                        continue
                    cid = room.clients.get(addr)
                    if cid is None:
                        if len(room.clients) >= MAX_CLIENTS_PER_ROOM:
                            continue
                        cid = len(room.clients) + 1
                        room.clients[addr] = cid
                        room.by_id[cid] = addr
                        print(f"sala {room_id}: cliente {cid} entrou ({addr[0]})", flush=True)
                    room.last = now
                    out = data[:5] + bytes([T_FROM]) + data[6:HEADER] + bytes([cid]) + data[HEADER:]
                    sock.sendto(out, room.host)
                    forwarded += 1
                elif typ == T_DOWN:
                    if room is None or room.host != addr or len(data) < HEADER + 2:
                        continue            # so o anfitriao da sala pode mandar para os clientes
                    dest = room.by_id.get(data[HEADER])
                    if dest is None:
                        continue
                    room.last = now
                    out = data[:5] + bytes([T_FROMHOST]) + data[6:HEADER] + data[HEADER + 1:]
                    sock.sendto(out, dest)
                    forwarded += 1

        if now - last_clean > 30:
            last_clean = now
            for rid in [r for r, rm in rooms.items() if now - rm.last > ROOM_TTL]:
                print(f"sala {rid} expirou", flush=True)
                del rooms[rid]
        if now - last_stats > 300:
            last_stats = now
            print(f"salas ativas: {len(rooms)}, pacotes repassados: {forwarded}", flush=True)


if __name__ == "__main__":
    main()
