# SSH test fixtures

TEST-ONLY keys, generated with `ssh-keygen` for `src/ssh/tests.rs`. They protect nothing, are not authorised anywhere,
and must never be used outside tests. Passphrase of the encrypted ones: `test-passphrase`.

| file | format |
| --- | --- |
| `rsa_pem_plain` | RSA, PKCS#1 PEM (`BEGIN RSA PRIVATE KEY`) |
| `rsa_pem_encrypted` | RSA, legacy PEM encryption (`Proc-Type: 4,ENCRYPTED`, AES-128-CBC) |
| `rsa_openssh` | RSA, OpenSSH container |
| `rsa_pkcs8_encrypted` | RSA, encrypted PKCS#8 |
| `ecdsa_sec1_pem` | ECDSA P-256, SEC1 PEM (`BEGIN EC PRIVATE KEY`) |
| `ed25519_openssh_encrypted` | Ed25519, OpenSSH container, bcrypt KDF + AES-256-CTR |
