# Roadmap

## Fase 1 — fundação Android

- [x] USB Host e detecção VID `2207`.
- [x] permissão Android.
- [x] inspeção de endpoints.
- [x] JNI e descritor USB.
- [x] backend root temporário somente leitura.
- [x] CI e testes básicos.

## Fase 2 — núcleo nativo somente leitura

- [ ] integrar libusb para Android.
- [ ] usar `libusb_wrap_sys_device`.
- [ ] portar transporte RockUSB.
- [ ] RCI, RID, RFI e RCB nativos.
- [ ] testes com `2207:320b` RK3228A.

## Fase 3 — backup confiável

- [ ] Read LBA em blocos.
- [ ] Storage Access Framework.
- [ ] checkpoint e retomada.
- [ ] SHA-256 e manifesto JSON.
- [ ] estimativa de tempo, velocidade e espaço.
- [ ] proteção contra suspensão/OTG timeout.

## Fase 4 — restauração e gravação controlada

- [ ] write LBA com allowlist de regiões.
- [ ] readback obrigatório.
- [ ] comparação de hash.
- [ ] confirmação dupla.
- [ ] fonte externa e bateria mínima.
- [ ] pacote de firmware com manifesto e hashes.

## Fase 5 — compatibilidade

- [ ] banco de VID/PID e SoCs Rockchip.
- [ ] Loader e MaskROM.
- [ ] NAND, eMMC e SPI.
- [ ] RK322x, RK3328, RK3399, RK356x e RK3588, conforme validação.

## Fase 6 — IA opcional

- [ ] explicar logs e erros.
- [ ] sugerir diagnóstico não destrutivo.
- [ ] funcionar sem IA.
- [ ] nunca executar gravações automaticamente.
