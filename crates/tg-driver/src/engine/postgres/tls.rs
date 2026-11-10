//! TLS for PostgreSQL with rustls on `ring`. `prefer` and `require` encrypt without checking the certificate, as
//! pgjdbc does; `verify-ca` and `verify-full` check it against the operating system's roots (both also check the
//! host name, which is stricter than libpq's `verify-ca`).

use crate::error::{DriverError, Result};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme};
use std::sync::Arc;
use tokio_postgres_rustls::MakeRustlsConnect;

pub type Tls = MakeRustlsConnect;

pub trait TlsChoice {
    fn none() -> Tls;
    fn unverified() -> Tls;
    fn verified() -> Result<Tls>;
}

fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn builder() -> rustls::ConfigBuilder<ClientConfig, rustls::WantsVerifier> {
    ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .expect("ring supports TLS 1.2 and 1.3")
}

impl TlsChoice for Tls {
    /// TLS isn't used with `sslmode=disable`; this only satisfies the connect call.
    fn none() -> Tls {
        Self::unverified()
    }

    fn unverified() -> Tls {
        let config = builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(AnyCertificate(provider())))
            .with_no_client_auth();
        MakeRustlsConnect::new(config)
    }

    fn verified() -> Result<Tls> {
        let mut roots = RootCertStore::empty();
        let found = rustls_native_certs::load_native_certs();
        for cert in found.certs {
            let _ = roots.add(cert);
        }
        if roots.is_empty() {
            return Err(DriverError::Connect(
                "No trusted certificates were found to verify the server with.".into(),
            ));
        }
        Ok(MakeRustlsConnect::new(
            builder()
                .with_root_certificates(roots)
                .with_no_client_auth(),
        ))
    }
}

/// Accepts any certificate but still checks the handshake signatures, like pgjdbc's `NonValidatingFactory`.
#[derive(Debug)]
struct AnyCertificate(Arc<rustls::crypto::CryptoProvider>);

impl ServerCertVerifier for AnyCertificate {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.0.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.0.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}
