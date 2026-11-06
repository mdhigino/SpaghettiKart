# Jogar pela internet com o seu VPS

1. No VPS, rode o relay (`tools/relay`, Dockerfile pronto) e publique a porta **UDP 27016**.
2. Libere UDP 27016 no firewall do provedor (e no do próprio servidor, se houver).
3. No app, em "Jogar pela internet", digite `IP_DO_VPS:27016`.
   - Anfitrião: "Criar sala online" -> aparece um código de 6 números.
   - Outros: digitam o mesmo endereço e o código, e tocam em "Entrar com código".
O atraso de entrada online é 6 quadros (no Wi-Fi local é 3).
