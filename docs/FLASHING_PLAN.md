# RockFlash DevTool — plano de gravação

Esta versão adiciona operações reais de gravação pelo backend `rkdeveloptool` instalado no Termux/root.

Modos previstos:

- imagem bruta completa (`wl 0`), destinada a imagens de disco compatíveis;
- imagem de partição por nome (`wlx`), para `boot`, `recovery`, `system`, `vendor` e outras partições existentes;
- carregamento temporário de loader (`db`);
- atualização persistente do loader (`ul`);
- gravação de GPT (`gpt`) e parâmetro Rockchip (`prm`);
- apagamento completo (`ef`);
- reinicialização do dispositivo (`rd`).

Uma imagem Armbian para cartão SD não é automaticamente compatível com NAND. O aplicativo exige confirmação explícita e não altera a compatibilidade do arquivo selecionado.
