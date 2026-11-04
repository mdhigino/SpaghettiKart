# Plano: SpaghettiKart no Android (e iPhone) com multiplayer local por Wi-Fi

Projeto pessoal, para jogar em família. O app NÃO inclui a ROM nem os assets do jogo:
cada pessoa fornece a sua própria ROM americana (.z64) e gera os arquivos `.o2r`.

## O que a análise do código mostrou

- O motor (libultraship) já tem caminhos para Android/iOS (`__ANDROID__`, `__IOS__`),
  `src/ship/port/mobile/MobileImpl.cpp` e `libultraship/cmake/dependencies/android.cmake`.
- Existe a opção de build `USE_OPENGLES` (OpenGL ES), necessária para celulares.
- SDL2_net já é dependência do projeto (útil para a rede).
- NÃO há código de rede no jogo. Só existe um item comentado no menu
  (`DISABLE_FOR_MULTIPLAYER_CONNECTED`, em `src/port/ui/PortMenu.cpp`).
- O jogo tem 8 vagas de corredor (`NUM_PLAYERS 8`), mas no máximo 4 humanos
  (`MAXCONTROLLERS 4`, `FOUR_PLAYERS_SELECTED 4`, split-screen de 4).
- Não existe projeto de app Android (Gradle) no repositório.

## Marcos

1. **Marco 1: 1 jogador no Android.** Projeto Android (SDL2) + build com `USE_OPENGLES`,
   APK compilado na nuvem pelo GitHub Actions. Os arquivos `mk64.o2r` e `spaghetti.o2r`
   são gerados no PC e copiados para o celular.
2. **Marco 2: controles.** Controle Bluetooth (via SDL) e botões virtuais de toque.
3. **Marco 3: 4 jogadores por Wi-Fi.** Um aparelho é o anfitrião (hotspot ou mesma rede);
   descoberta por UDP broadcast ou IP digitado. Método lockstep: cada aparelho envia só os
   botões e todos simulam a mesma corrida, com atraso de entrada de 2 a 3 quadros e
   verificação de dessincronização.
4. **Marco 4: 8 jogadores.** Ampliar de 4 para 8 humanos na entrada de controles, nos menus
   e na lógica. Cada aparelho desenha só a sua tela, então o limite de split-screen não importa.
5. **Marco 5: lobby.** Criar sala, entrar, escolher personagem em cada aparelho, iniciar.
6. **Marco 6: iPhone.** Build iOS (Metal) pelo GitHub Actions em máquina macOS,
   com instalação por sideload. A rede é a mesma do Android.

## Riscos conhecidos

- Os 8 humanos são a parte mais incerta; o tamanho real só aparece depois do Marco 3.
- Dessincronização (lockstep) entre aparelhos diferentes; testar com Android e iPhone juntos.
- Desempenho do hotspot com 8 aparelhos.
