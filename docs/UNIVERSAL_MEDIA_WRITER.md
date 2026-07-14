# Universal Media Writer

## Origem e licenciamento

A arquitetura foi estudada a partir do EtchDroid, que é distribuído sob GPLv3. O RockFlashingTool permanece sob AGPLv3. Nesta etapa, o transporte SCSI foi implementado sobre a infraestrutura Bulk-Only já existente no projeto; nenhum serviço GPL do EtchDroid foi copiado.

O EtchDroid usa a biblioteca libaums para USB Mass Storage. A implementação atual do RockFlashingTool usa diretamente `UsbDeviceConnection`, CBW/CSW e comandos SCSI, permitindo controlar os limites e adicionar READ/WRITE(16).

## Matriz de suporte

| Destino ou formato | Estado inicial | Observação técnica |
|---|---|---|
| Pendrive USB BOT | Suportado pelo backend | SCSI Transparent, protocolo Bulk-Only `0x50` |
| Leitor SD conectado por USB | Suportado pelo backend | Desde que apareça como SCSI BOT |
| HDD/SSD USB | Suportado pelo backend | O gabinete precisa oferecer BOT; alimentação externa pode ser necessária |
| Dispositivo atrás de hub | Suportado pelo scanner | O Android deve enumerar o dispositivo downstream; energia continua sendo limitação física |
| Mídia acima de 2 TiB | Suportado pelo protocolo | READ CAPACITY(16), READ(16) e WRITE(16), com LBA de 64 bits |
| UAS/UASP | Detectado, ainda não gravável | Exige transporte UAS e filas de comandos, diferente do BOT |
| Slot microSD interno do telefone | Root experimental futuro | Android normalmente não expõe o bloco bruto a aplicativos; deve haver validação rigorosa de `/sys/block/*/removable` |
| Unidade óptica USB | Detectada como ATAPI | Gravar CD/DVD/BD exige comandos MMC, sessões, tracks e finalização; não é escrita de bloco comum |
| Unidade de disquete USB | Detectada como UFI | Requer subclass UFI, geometria e comandos específicos |
| Thunderbolt-only | Não implementável por aplicativo | Depende de controladora e drivers Thunderbolt no próprio telefone |
| ISO Linux híbrida | Suportada | Deve conter ISO9660 e MBR/GPT híbrida |
| ISO comum não híbrida | Conversão necessária | A cópia setor a setor pode não gerar mídia inicializável |
| ISO oficial do Windows | Criador de mídia necessário | Exige GPT/MBR, FAT32/NTFS, extração ISO e possível divisão de `install.wim` |
| Apple DMG | Conversão necessária | UDIF pode usar compressão, criptografia, checksums e chunks não contíguos |
| Android sparse image | Conversão necessária | Deve ser expandida para RAW |
| QCOW2/VHD/VHDX | Conversão necessária | Imagens virtuais possuem metadados e alocação esparsa |

## Componentes da versão 0.8

### `ScsiProtocol`

- INQUIRY;
- TEST UNIT READY;
- READ CAPACITY(10);
- READ CAPACITY(16);
- READ/WRITE(10);
- READ/WRITE(16);
- serialização big-endian de LBA de 64 bits.

### `AndroidScsiBlockDevice`

Abre uma interface `USB_CLASS_MASS_STORAGE`, subclass SCSI Transparent e protocolo Bulk-Only. Expõe capacidade, identificação e leitura/gravação em blocos alinhados.

### `ScsiRawImageWriter`

- grava em chunks limitados;
- preenche com zeros apenas o último bloco incompleto;
- reabre a origem;
- realiza readback completo;
- informa o byte exato da primeira divergência.

### `UsbMediaScanner`

Classifica BOT, UAS, UFI, ATAPI, CBI e combinações desconhecidas. Hubs não são tratados como mídia: cada dispositivo downstream é enumerado individualmente pelo Android.

### `DiskImageInspector`

Reconhece e bloqueia gravações incorretas de:

- ISO não híbrida;
- ISO do Windows;
- DMG;
- Android sparse;
- QCOW2;
- VHD/VHDX;
- arquivos compactados;
- contêineres Rockchip RKFW/RKAF.

## Próximas etapas

1. integrar seleção de destino USB na interface Compose;
2. solicitar permissão individual para o dispositivo de destino;
3. executar `ScsiRawImageWriter` no foreground service;
4. adicionar múltiplos LUNs para docks e leitores multicartão;
5. implementar REQUEST SENSE e recuperação de stall/reset BOT;
6. testar HDD/SSD com alimentação própria;
7. implementar criador de mídia Windows como fluxo separado;
8. avaliar conversor DMG para os tipos UDIF sem criptografia;
9. manter UAS, óptico, disquete e slot interno em backends separados.

## Regra de segurança

Nenhum formato marcado como “conversão necessária” deve ser liberado por uma simples troca de extensão. O conversor precisa produzir um fluxo RAW verificável e informar exatamente o layout criado no destino.
