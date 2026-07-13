# RockFlash DevTool

Aplicativo Android para diagnosticar, fazer backup e administrar dispositivos Rockchip em Loader/MaskROM diretamente pelo USB OTG do celular.

O repositório mantém o nome `Rockship-DevTool`, mas o produto usa o nome **RockFlash DevTool**.

## Estado atual — 0.3.0-alpha01

Funcionalidades disponíveis:

- detecção de dispositivos com VID Rockchip `0x2207`;
- reconhecimento inicial de `2207:320b` como Loader RK322x;
- permissão USB pelo Android e inspeção de interfaces/endpoints;
- backend JNI em desenvolvimento e backend root funcional com `rkdeveloptool` do Termux;
- informações do chip, flash, capacidades e partições (`LD`, `RCI`, `RID`, `RFI`, `RCB`, `PPT`);
- backup completo por `RL`, validação de tamanho e SHA-256;
- gravação de imagem bruta por LBA (`WL`);
- gravação de partição por nome (`WLX`);
- carregamento e atualização de loader (`DB` e `UL`);
- gravação de GPT e `parameter`;
- apagamento completo (`EF`) e reinicialização (`RD`), ambos com confirmação;
- seleção e descompactação de `.img.xz`;
- gravação e verificação de imagens em pendrive removível `/dev/sdX`;
- revalidação do pendrive imediatamente antes do apagamento;
- assistente Armbian RK322x para NAND/eMMC;
- validação estrutural do `u-boot-main.img` antes de gravá-lo no LBA `0x4000`;
- preparo de Multitool e cópia da imagem Armbian para a pasta `images`;
- relatório JSON, WakeLock e verificações de bateria/alimentação;
- CI com testes, compilação e Android Lint.

## Assistente Armbian RK322x

O botão **Assistente Armbian RK322x** organiza o procedimento em etapas:

1. detectar a TV Box e o armazenamento;
2. criar ou confirmar um backup completo;
3. selecionar `u-boot-main.img`, Armbian e Multitool;
4. gravar Armbian ou Multitool em um pendrive removível;
5. copiar a imagem Armbian para `images`;
6. instalar o bootstrap USB na NAND no LBA `0x4000`.

Para NAND RK322x, a instalação interna completa ainda termina dentro do Multitool com **Burn Armbian image via steP-nand**, pois essa etapa depende do driver proprietário `rknand` disponível no ambiente legacy. Imagens mainline/current podem ser executadas pelo USB sem serem gravadas diretamente na NAND.

## Segurança

Operações de gravação são reais e podem impedir o boot. O aplicativo aplica:

- confirmação explícita;
- bateria e alimentação mínima;
- backup obrigatório no assistente Armbian;
- validação de arquivo, caminho, setor e partição;
- validação estrutural do bootstrap;
- revalidação de identidade do pendrive;
- verificação binária após gravar o pendrive;
- bloqueio de destinos que não sejam `/dev/sdX` removíveis.

O comando `EF` apaga toda a flash e só deve ser usado quando houver firmware de recuperação compatível e backup verificado.

## Requisitos do backend root atual

- Android com root;
- Termux com `rkdeveloptool` instalado em:
  `/data/data/com.termux/files/usr/bin/rkdeveloptool`;
- bibliotecas em `/data/data/com.termux/files/usr/lib`;
- utilitários Termux como `dd`, `cmp` e `sha256sum`;
- TV Box em Loader/MaskROM;
- USB OTG e alimentação externa estável.

O objetivo de longo prazo é substituir o backend Termux por um núcleo nativo Android usando USB Host, JNI e libusb.

## Build

Requisitos:

- JDK 17;
- Android SDK 35;
- NDK `27.0.12077973`;
- CMake `3.22.1`;
- Gradle 8.9.

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Arquitetura e planejamento

- [Arquitetura](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Segurança](SECURITY.md)
- [Política de IA](docs/AI_POLICY.md)
- [Plano de gravação](docs/FLASHING_PLAN.md)

## Licença

O repositório utiliza AGPL-3.0. Componentes de terceiros mantêm suas licenças originais.
