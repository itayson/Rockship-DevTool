# Diagnóstico e solução de problemas

## `Cannot run program "su": error=2`

O Android não encontrou um executável `su` disponível para o aplicativo. Isso ocorre quando:

- o celular não está rooteado;
- Magisk, KernelSU ou APatch não está instalado corretamente;
- o acesso root do RockFlash foi negado;
- a ROM oculta o `su` do processo do aplicativo.

A partir da versão 0.4.0, esse cenário não causa mais exceção bruta. O aplicativo mostra um diagnóstico estruturado e desativa operações que exigem privilégios.

### Como corrigir o backend legado

1. Confirme que o gerenciador de root está funcionando.
2. Abra o RockFlash e conceda root quando solicitado.
3. Instale o `rkdeveloptool` no Termux.
4. Confirme a existência de um dos caminhos:

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
- mudar permissão de `/dev/bus/usb/...`;
- acessar `/dev/sdX` para gravar pendrive em modo bruto.

A remoção dessas dependências exige dois backends nativos: protocolo RockUSB usando `UsbDeviceConnection`/libusb e gravação USB Mass Storage por comandos SCSI. Isso está sendo tratado separadamente para evitar operações destrutivas parcialmente implementadas.
