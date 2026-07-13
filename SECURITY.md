# Segurança

RockFlash interage com bootloaders e memória flash. Uma operação incorreta pode impedir o boot do dispositivo.

## Política atual

A versão alpha oferece leitura, backup, gravação, atualização de loader e apagamento. As operações destrutivas exigem confirmação e não devem ser tratadas como universais entre placas Rockchip.

Proteções implementadas:

- comandos construídos por enum e parâmetros validados;
- nomes de partição restritos;
- bateria mínima, carregamento e economia de energia verificados;
- backup obrigatório no assistente Armbian ou confirmação de backup externo;
- backup aceito somente quando o tamanho corresponde exatamente ao esperado;
- SHA-256 do backup completo;
- validação estrutural do bootstrap RK322x;
- gravação do bootstrap em endereço fixo `0x4000` somente no fluxo NAND;
- destinos USB restritos a blocos removíveis `/dev/sdX`;
- tamanho, fabricante, modelo e serial do pendrive revalidados antes de apagar;
- comparação binária obrigatória depois de gravar uma imagem no pendrive;
- WakeLock durante operações longas.

## Operações críticas

- `EF` apaga toda a flash.
- `UL` altera o loader persistente.
- `GPT` e `PRM` alteram o particionamento.
- `WL 0` pode substituir bootloader, partições e sistema completo.
- interromper energia, USB ou o processo durante escrita pode inutilizar o boot.

Não use uma ROM de outro modelo apenas porque o SoC é semelhante. Confira placa, armazenamento, DDR, Wi-Fi, DTB, loader, tamanho e layout de partições.

## Armbian em NAND RK322x

O aplicativo prepara os arquivos, pendrive, backup e bootstrap. A instalação interna pelo método `steP-nand` deve ser concluída no Multitool legacy, que possui acesso ao dispositivo `rknand`. Não grave uma imagem Armbian mainline diretamente no setor zero da NAND.

## Relato de falhas

Não publique backups, chaves, tokens, dumps privados ou dados pessoais em issues. Descreva:

- versão do aplicativo;
- modelo/SoC e revisão da placa;
- NAND/eMMC e capacidade;
- VID:PID;
- modo Loader ou MaskROM;
- comando executado;
- nome e hash do arquivo utilizado;
- log sem dados sensíveis.
