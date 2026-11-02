#!/usr/bin/env bash
#
# Baixa o modelo do MediaPipe Hand Landmarker para os assets ESTÁTICOS do app, em
# `app/src/main/assets/hand_landmarker.task`.
#
# Quando usar isto em vez de deixar o Gradle baixar sozinho:
#   - você está offline no momento do build, mas tem como transferir o arquivo;
#   - sua rede intercepta HTTPS (proxy corporativo) e o download do Gradle falha;
#   - você quer inspecionar/verificar o modelo antes de colocá-lo no APK.
#
# Como o build reage a este arquivo: a tarefa `FetchHandModelTask` declara este caminho como
# `@InputFile @Optional manualModel`. Quando o arquivo existe e não está vazio, a tarefa não
# baixa nada e deixa o diretório gerado vazio de propósito. Isso é o que evita que os dois
# caminhos produzam `hand_landmarker.task` e o merge de assets falhe com arquivo duplicado —
# sem essa guarda, usar este fallback quebraria o build em vez de salvá-lo.
#
# ATENÇÃO: não aponte este script para `app/build/…`. O build não lê mais aquele caminho desde
# que os assets gerados passaram a ser registrados por variant via
# `addGeneratedSourceDirectory` (o AGP 9 proíbe `Provider` no SourceSet API). Escrever lá
# produziria um download que ninguém empacota, e o APK sairia sem o modelo — uma falha silenciosa
# que só aparece em runtime, com o hand tracking inativo.

set -euo pipefail

URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

# Resolve a raiz do projeto a partir da localização deste script, para funcionar de qualquer
# diretório de trabalho.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

OUT_DIR="$PROJECT_ROOT/app/src/main/assets"
OUT_FILE="$OUT_DIR/hand_landmarker.task"

if [ -s "$OUT_FILE" ]; then
  echo "Modelo já presente: $OUT_FILE"
  echo "  tamanho: $(wc -c < "$OUT_FILE") bytes"
  echo "Para forçar o download de novo, apague o arquivo e rode este script outra vez."
  exit 0
fi

mkdir -p "$OUT_DIR"
TMP="$OUT_FILE.part"

echo "Baixando $URL"
# `-f` faz o curl falhar em HTTP 4xx/5xx em vez de salvar a página de erro como se fosse o
# modelo — que é exatamente o tipo de falha silenciosa que depois aparece como
# "createFromOptions estourou" sem explicação.
curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 -o "$TMP" "$URL"

SIZE="$(wc -c < "$TMP")"
# O hand_landmarker.task (float16) tem ~7 MB. Menos de 1 MB indica resposta de erro.
if [ "$SIZE" -lt 1000000 ]; then
  echo "ERRO: download suspeito — $SIZE bytes (esperado ~7 MB)." >&2
  rm -f "$TMP"
  exit 1
fi

mv "$TMP" "$OUT_FILE"

echo "OK: $OUT_FILE ($SIZE bytes)"
if command -v sha256sum >/dev/null 2>&1; then
  echo "sha256: $(sha256sum "$OUT_FILE" | cut -d' ' -f1)"
fi
