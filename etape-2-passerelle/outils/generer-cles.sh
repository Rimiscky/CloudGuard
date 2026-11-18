#!/usr/bin/env bash
# Genere la paire de cles RSA qui signe et verifie les jetons (RS256).
# La cle privee reste chez l'emetteur des jetons ; la passerelle ne
# recoit que la cle publique.
set -euo pipefail
cd "$(dirname "$0")/../cles"
openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out prive.pem
openssl pkey -in prive.pem -pubout -out public.pem
echo "Cles ecrites dans $(pwd) : prive.pem et public.pem"
