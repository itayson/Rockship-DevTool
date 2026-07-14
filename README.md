# RockFlash DevTool

Aplicativo Android para diagnosticar, fazer backup e administrar dispositivos Rockchip em Loader/MaskROM diretamente pelo USB OTG do celular.

O repositório mantém o nome `Rockship-DevTool`, mas o produto usa o nome **RockFlash DevTool**.

## Estado atual — 0.4.0-alpha01

Funcionalidades disponíveis:

- detecção de dispositivos com VID Rockchip `0x2207`;
- reconhecimento inicial de `2207:320b` como Loader RK322x;
- permissão USB pelo Android e inspeção de interfaces/endpoints;
- detecção Android USB Host de TV Box e pendrive mesmo sem root;
- diagnóstico estruturado de root para Magisk, KernelSU e APatch;
- distinção entre root ausente, root negado, falha de execução e `rkdeveloptool` ausente;
- detecção automática dos caminhos comuns do `rkdeveloptool` no Termux;
- backend JNI em desenvolvimento e backend root funcional com `rkdeveloptool` do Termux;
- informações do chip, flash, capacidades e partições (`LD`, `RCI`, `RID`, `RFI`, `RCB`, `PPT`);
- backup completo por `RL`, validação de tamanho e SHA-256;
- gravação de imagem bruta por LBA (`WL`);
- gravação de partição por nome (`WLX`);
- carregamento e atualização de loader (`DB` e `UL`);
- gravação de GPT e `parameter`;
- apagamento completo (`EF`) e reinicialização (`RD`), ambos com confirmação;
- seleção e descompactação de `.img.xz` sem depender de root;
- gravação e verificação de imagens em pendrive removível `/dev/sdX` quando root está disponível;
- revalidação do pendrive imediatamente antes do apagamento;
- assistente Armbian RK322x para NAND/eMMC;
- validação estrutural do `u-boot-main.img` antes de gravá-lo no LBA `0x4000`;
- preparo de Multitool e cópia da imagem Armbian para a pasta `images`;
- relatório JSON, WakeLock e verificações de bateria/alimentação;
- encerramento controlado de operações root e seus descendentes quando há timeout;
- CI com testes, compilação, Android Lint e preservação dos logs de falha.

## Correção do erro `Cannot run program "su": error=2`

A versão 0.4 não deixa mais essa exceção bruta interromper o fluxo. Quando o Android não encontra `su`, o aplicativo:

1. informa que root não foi encontrado;
2. mantém seleção, validação e descompactação de imagens funcionando;
3. mantém a detecção de USB pelo Android funcionando;
4. desativa apenas as operações que realmente dependem de root;
5. mostra o código e a causa do backend indisponível.

Consulte [Diagnóstico e solução de problemas](docs/TROUBLESHOOTING.md).

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
- invalidação do backup ao trocar de TV Box;
- validação de arquivo, caminho, setor e partição;
- validação estrutural do bootstrap;
- revalidação de identidade do pendrive;
- limpeza da seleção antiga ao procurar outro pendrive;
- verificação binária após gravar o pendrive;
- bloqueio de destinos que não sejam `/dev/sdX` removíveis;
- códigos separados para root indisponível e backend `rkdeveloptool` indisponível;
- nenhuma alteração permanente de permissões no node USB.

O comando `EF` apaga toda a flash e só deve ser usado quando houver firmware de recuperação compatível e backup verificado.

## Requisitos do backend root atual

- Android com root por Magisk, KernelSU, APatch ou solução compatível;
- permissão root concedida ao RockFlash;
- Termux com `rkdeveloptool` instalado em um dos caminhos:
  - `/data/data/com.termux/files/usr/bin/rkdeveloptool`;
  - `/data/user/0/com.termux/files/usr/bin/rkdeveloptool`;
- bibliotecas na pasta `lib` correspondente ao Termux;
- utilitários Termux como `dd`, `cmp` e `sha256sum`;
- TV Box em Loader/MaskROM;
- USB OTG e alimentação externa estável.

O backend atual não transforma um celular sem root em acesso raw a `/dev/sdX`. A remoção dessa dependência exige dois componentes nativos ainda em desenvolvimento:

- transporte RockUSB por Android USB Host/JNI/libusb;
- gravador USB Mass Storage por Bulk-Only Transport e comandos SCSI.

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
- [Solução de problemas](docs/TROUBLESHOOTING.md)

## Licença

O repositório utiliza AGPL-3.0. Componentes de terceiros mantêm suas licenças originais.
