# BrunoFrameOverlay V3.7

V3.7 foca em HUD FPS móvel, painel limpo e layout neon.

## Principais mudanças

- Render Scale 50% a 100%.
- Padrão 85% para reduzir pixels processados.
- Overlay continua fullscreen; o frame interno é reduzido e depois escalado.
- Modo economia de bateria ativado por padrão.
- Menos cópia de bitmap fullscreen em modo Native/Vulkan.
- HUD/painel atualizam menos vezes para reduzir CPU.
- SurfaceFlinger/Shizuku com polling mais lento.

## Importante

Este ZIP completo não inclui a `libbrunolsfg.so` compilada. Se você usa o projeto completo, copie sua `.so` funcional para:

```txt
app/src/main/jniLibs/arm64-v8a/libbrunolsfg.so
```

Se aplicar o patch-only por cima do projeto atual, a `.so` existente será preservada.

## Recomendações

- Brawl Stars: Render 85%, Multiplier 2x, Flow 0.25~0.35.
- Free Fire: Render 75~85%, Multiplier 2x ou 3x.
- NTE/Genshin: Render 75%, Multiplier 2x.


## V3.7
- HUD FPS separado, arrastável, redimensionável e com duplo toque para fixar/desfixar.
- Painel grande removido do overlay fixo; painel completo permanece apenas pela bolha.
- Bolha/painel com visual neon/cyber inspirado no layout enviado.
- Mensagens temporárias não ficam piscando no painel.
- HUD mostra apenas `FPS jogo/FPS IA`.
