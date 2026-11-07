# Relay online do MK64 Família

Servidor UDP que repassa os pacotes entre os celulares (quando não estão no mesmo Wi-Fi).
Não roda o jogo; só encaminha, usando o código de sala de 6 números.

* Porta padrão: **UDP 27016** (variável `RELAY_PORT`).
* Easypanel: Dockerfile = `Dockerfile.relay` (na raiz do repositório). Veja `docs/ONLINE_VPS.md`.
