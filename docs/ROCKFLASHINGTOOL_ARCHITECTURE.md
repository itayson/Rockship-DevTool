# RockFlashingTool — arquitetura inicial

## Objetivo

Aplicativo Android nativo para operar dispositivos Rockchip em dois contextos:

1. **USB Host/OTG:** o Android controla uma TV Box em MaskROM ou Loader por RockUSB.
2. **Local Root:** o aplicativo executado na própria TV Box acessa blocos internos por libsu.

A escrita destrutiva deve permanecer atrás de validação de dispositivo, energia, tamanho, intervalo LBA, confirmação explícita e verificação por leitura.

## Estrutura

```text
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── native_bridge.cpp
├── java/com/tayson/rockflash/
│   ├── RockFlashingApplication.kt
│   ├── core/
│   │   └── FlashingSessionState.kt
│   ├── firmware/
│   │   ├── PackageFileParser.kt
│   │   └── ParameterParser.kt
│   ├── root/
│   │   └── LocalNandManager.kt
│   ├── ui/
│   │   ├── RockFlashingToolActivity.kt
│   │   └── RockFlashingToolScreen.kt
│   └── usb/
│       ├── AndroidBulkOnlyTransport.kt
│       ├── BulkOnlyProtocol.kt
│       ├── RockUsbDirectBackend.kt
│       ├── RockUsbOperations.kt
│       ├── RockchipModeDetector.kt
│       ├── UsbAttachReceiver.kt
│       └── UsbHostForegroundService.kt
└── AndroidManifest.xml
```

## Responsabilidades

### `ui`

Jetpack Compose. Exibe estado da conexão, firmware selecionado, partições e logs. Não conhece endpoints USB, comandos root ou offsets de contêiner.

### `core`

Modelos imutáveis e `StateFlow` compartilhado. Não armazena `UsbDeviceConnection`, descritores de arquivo nem buffers de firmware.

### `usb`

- descoberta VID `0x2207`;
- autorização por `UsbManager.requestPermission()`;
- detecção MaskROM/Loader pelo bit 0 de `bcdUSB`, igual ao rkdeveloptool oficial;
- transporte Bulk-Only e comandos RockUSB;
- contrato `RockUsbOperations` para desacoplar protocolo e UI.

### `root`

Acesso local via libsu. A fundação habilita descoberta e backup. Gravação local deve ser adicionada somente junto com uma SafetyGate específica e testes em hardware descartável.

### `firmware`

- parser estrito de `parameter.txt`/`mtdparts`;
- parser seguro do manifesto `package-file`;
- contrato para o extrator binário RKFW/RKAF.

### `cpp`

JNI/C++ permanece reservado para operações que realmente precisem de código nativo, buffers alinhados ou integração libusb. O transporte Android atual usa `UsbDeviceConnection`, evitando duplicar pilha USB sem necessidade.

## Segurança mínima para escrita

Antes de `wl`, erase, GPT, parameter ou escrita local:

1. confirmar VID/PID, estágio e capacidade;
2. validar intervalo LBA com aritmética protegida contra overflow;
3. impedir escrita além da capacidade reportada;
4. exigir bateria/carga adequadas;
5. exigir confirmação digitada vinculada ao dispositivo e à operação;
6. gravar em blocos limitados e cancelar com estado consistente;
7. executar readback e comparar os dados;
8. registrar hash, offsets, duração e resultado.

## Testes iniciais

- `ParameterParserTest`: hexadecimal, decimal, duplicidade, sobreposição e partição restante;
- `PackageFileParserTest`: manifesto válido, duplicidade e path traversal;
- `RockchipModeDetectorTest`: classificação MaskROM/Loader por `bcdUSB`;
- testes existentes de CBW/CSW e comandos RockUSB permanecem ativos.
