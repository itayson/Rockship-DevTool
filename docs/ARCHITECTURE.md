# Arquitetura

```text
MainActivity / UI
      |
      +-- RockchipUsbController
      |      +-- UsbManager
      |      +-- UsbDeviceConnection
      |      +-- interfaces/endpoints
      |
      +-- NativeBridge (JNI)
      |      +-- file descriptor duplicado
      |      +-- futuro libusb_wrap_sys_device
      |      +-- futuro protocolo RockUSB
      |
      +-- RkDevelopToolBackend (temporário/root)
      |      +-- allowlist somente leitura
      |      +-- su -c
      |      +-- rkdeveloptool do Termux
      |
      +-- SafetyGate
             +-- bateria
             +-- carregamento
             +-- economia de energia
```

## Regras

1. A UI não chama shell diretamente.
2. O backend root aceita somente enum de comandos permitidos.
3. Operações de escrita exigirão outro componente, outra tela e confirmação separada.
4. IA nunca terá acesso direto aos métodos de gravação.
5. Toda escrita futura será seguida de readback e verificação.
6. Backup será feito por blocos com checkpoint e SHA-256.
