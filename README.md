# Bóveda Segura

Gestor de contraseñas y cofre de documentos para Android, 100 % offline (sin permiso de Internet).

## Fase 1

- **Bóveda**: logins, tarjetas, cuentas bancarias, identidades, notas, Wi-Fi, servidores/BD, claves SSH/API, licencias.
- **Autocompletado** nativo de Android + búsqueda manual que aprende la app/sitio, guardado sin duplicados,
  aviso cuando Android lo desactiva, mosaico de acceso rápido en la barra de notificaciones.
- **2FA (TOTP)** integrado, con escaneo de QR; se copia el código al autocompletar.
- **Centro de seguridad**: puntaje, débiles, repetidas, antiguas, sin 2FA.
- **Historial** de contraseñas y **papelera** de 30 días.
- **Archivos seguros**: cofre cifrado (imágenes, PDF, Word…), visor interno de imágenes/PDF/texto,
  cámara segura (foto y documento multipágina a PDF), exportación con reautenticación,
  compartir cifrado `.bsf` con código y vencimiento, borrado opcional del original.
- **Respaldos**: (1) Bóveda `.vault` – cuentas, documentos, cámara y escaneos – diario y tras 5 cambios,
  7 diarios + 4 semanales, verificado; (2) Multimedia – imágenes importadas – incremental, con retención de 30 días.
- **Modo viaje**, **bóveda señuelo** (contraseña de coacción), **autodestrucción**, biometría con Keystore.
- **Importar** CSV de Chrome/Google, 1Password, RoboForm, Keeper, Bitwarden.

## Criptografía

- KEK = HKDF-SHA256( Argon2id(contraseña, sal; 32 MiB, t=3), clave secreta de 128 bits )
- La KEK cifra la llave de la bóveda; cada registro y cada archivo tiene su propia llave AES-256-GCM.
- La clave secreta vive en el teléfono (protegida por Android Keystore) y en el Kit de emergencia; nunca en los respaldos.
- Archivos: AES-256-GCM en bloques de 64 KB con detección de truncamiento.

## Compilar

GitHub Actions compila el APK en cada push a `main` (pestaña *Actions* › artefacto `BovedaSegura-APK`).
Para firmar con una llave estable, define los secretos `BS_KEYSTORE_B64`, `BS_KEYSTORE_PASSWORD`, `BS_KEY_ALIAS`, `BS_KEY_PASSWORD`.
