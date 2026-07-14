# Diagnóstico e solução de problemas

## `Cannot run program "su": error=2`

O Android não encontrou um executável `su` disponível para o aplicativo. Isso pode ocorrer quando:

- o celular não está rooteado;
- Magisk, KernelSU ou APatch não está instalado corretamente;
- o acesso root do RockFlash foi negado;
- a ROM oculta o `su` do processo do aplicativo;
- uma versão anterior do RockFlash descartou incorretamente o `su` virtual antes de tentar executá-lo.

### KernelSU/APatch mostra Superusuário ativado, mas o RockFlash informa código 126

KernelSU e APatch podem disponibilizar `/system/bin/su` ou `/system/bin/kp` virtualmente durante a chamada `execve`. Nessas implementações, verificações Java como `File.exists()` ou `File.canExecute()` podem retornar falso mesmo quando o gerenciador já autorizou o aplicativo.

A versão **0.4.0-alpha02** remove esse preflight e tenta diretamente:

```text
/system/bin/su
/system/bin/kp
/system/xbin/su
/sbin/su
/su/bin/su
/debug_ramdisk/su
/data/adb/ksu/bin/su
su
kp
```

Também passa a mostrar cada caminho tentado no diagnóstico. O perfil autorizado precisa corresponder ao pacote instalado:

```text
Debug:   com.tayson.rockflash.debug
Release: com.tayson.rockflash
```

### Como resolver

1. Atualize para `0.4.0-alpha02` ou superior.
2. Confirme que o Superusuário está habilitado para o pacote correto.
3. Force a parada do RockFlash e abra novamente depois de alterar a autorização.
4. No Assistente Armbian, toque em **Atualizar estado**.
5. Depois que o root for reconhecido, instale o `rkdeveloptool` no Termux.
6. Confirme a existência de um dos caminhos:

```text
/data/data/com.termux/files/usr/bin/rkdeveloptool
/data/user/0/com.termux/files/usr/bin/rkdeveloptool
```

## O que funciona sem root

- seleção e validação de imagens;
- descompactação `.xz`;
- detecção Android USB Host de dispositivos Rockchip;
- detecção Android USB Host de pendrives Mass Storage;
- geração de relatórios e diagnóstico.

## O que ainda exige root no backend atual

- executar o `rkdeveloptool` instalado no Termux;
- acessar `/dev/sdX` para gravar pendrive em modo bruto.

A remoção dessas dependências exige dois backends nativos: protocolo RockUSB usando `UsbDeviceConnection`/libusb e gravação USB Mass Storage por comandos SCSI. Isso está sendo tratado separadamente para evitar operações destrutivas parcialmente implementadas.
