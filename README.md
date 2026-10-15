# MailGuard
![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green?style=for-the-badge&logo=springboot)
![Maven](https://img.shields.io/badge/Maven-Build-red?style=for-the-badge&logo=apachemaven)
![MySQL](https://img.shields.io/badge/MySQL-8-blue?style=for-the-badge&logo=mysql)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-green?style=for-the-badge&logo=swagger)

MailGuard exposes a synchronous, passive email check: local format validation followed by one DNS MX observation. It does not verify a mailbox or send email.

This repository preserves the history and attribution of [the original MailGuard](https://github.com/aasheesh06/MailGuard). The broader email intelligence architecture in [PLAN.md](PLAN.md) is planned, not implemented.

---
## ✨ Features
- ✔ Email format validation
- ✔ Domain extraction and MX observation using DNS
- ✔ Explicit unknown DNS outcome; no SMTP connection
- ✔ REST API built with Spring Boot
- ✔ Swagger/OpenAPI documentation
- ✔ MySQL validation history
- ✔ Maven project

## 📦 Installation
Clone the repository:

```bash
git clone https://github.com/soumaydutt/mail-intelligence-platform.git
```

Move into the project directory:

```bash
cd mail-intelligence-platform
```

Requires Java 21 and Maven. The current application needs a MySQL database with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` set; tests use an isolated in-memory H2 database.
Keep credentials in `DB_USERNAME` and `DB_PASSWORD`, not in `DB_URL`: the JDBC URL appears in startup logs. A disposable MySQL 8.4 container passed startup and two synthetic history writes; production data remains unverified.

Build the project:

```bash
mvn clean install
```

## 🚀 Quick Start
Start the Spring Boot application:

```bash
mvn spring-boot:run
```

Once the application starts successfully, open your browser:

```
http://localhost:8080/swagger-ui/index.html
```

You can now test all available email validation APIs directly from Swagger UI.

## 📖 Usage
MailGuard checks local email format, then observes DNS MX records for format-valid input. It does not connect to recipient mail servers. Requests within the 255-character storage limit retain history writes. Longer values and malformed JSON return HTTP 400 before DNS or persistence; format-invalid email strings retain HTTP 200 with `status=INVALID`.

## 📧 Email Validation
### Validate an Email Address

Request

```http
POST /api/email/validate
```

Request Body

```json
{
  "email": "john@example.com"
}
```

Invalid request bodies return HTTP 400 with a fixed message; unexpected errors return HTTP 500 with a generic message. Error responses retain `timestamp`, `status`, `message`, and `path` fields. Known API routes retain their path; unknown paths are redacted to `/`.

Response when an MX record is observed (example):

```json
{
  "email": "john@example.com",
  "domain": "example.com",
  "formatValid": true,
  "mxRecordFound": true,
  "smtpVerified": null,
  "status": "UNVERIFIED",
  "message": "MX record observed; mailbox not verified."
}
```

## 🌐 MX Record Lookup
One DNS MX lookup reports `mxRecordFound=true` for a non-null MX, `false` for no MX/NXDOMAIN or a null MX, and `null` for timeout, server failure, or unparseable domain. A missing MX does not prove mailbox invalidity; an MX record does not prove mailbox existence.

`status` is `INVALID` only for local format failures. Any format-valid input returns `UNVERIFIED`; `smtpVerified` is always `null`. The API performs no SMTP probe or email sending.

## Supported Email Syntax
MailGuard accepts ASCII dot-atoms (including `+` tags) and whole quoted ASCII local parts with printable escaped characters and quoted spaces. It rejects comments, folding whitespace, domain literals, SMTPUTF8 local parts, empty components, and unquoted spaces. This is a supported subset of RFC 5321/5322, not a full RFC parser.

Domains require at least two public-hostname labels and a nonnumeric final ASCII label; dotted IPv4 text and numeric TLDs are rejected. Unicode domain labels are NFC-normalized and converted to lowercase ASCII IDNA labels for DNS, the response `domain`, and history `domain`; the original address remains unchanged in the response and existing history. Java IDN uses IDNA2003/Nameprep with STD3 checks and round-trip validation, not full IDNA2008. These checks do not establish public TLD registration, mailbox reachability, or homograph safety.

Limits apply to the normalized SMTP ASCII representation: local part 64 bytes, each domain label 63 bytes, domain 253 bytes, total address 254 bytes. Raw input above 254 UTF-16 code units is unsupported. Address syntax failures within the request size limit return HTTP 200 and `status=INVALID`; the separate 255-character request limit still returns HTTP 400 when exceeded. Structured reason codes are internal; public response fields are unchanged.

## 📚 REST API
### Base URL

```
http://localhost:8080
```

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/email/validate` | Validate an email address |

## 📂 Project Structure
The root Maven project contains the Spring Boot API in `src/main/java/com/aash/mailguard` and offline tests in `src/test/java`.

## 🛠 Technologies
- Java 21
- Spring Boot 4
- Spring Data JPA
- MySQL
- Maven
- DNSJava
- Swagger (OpenAPI)
- Lombok

## 📄 License
MIT License

Copyright (c) 2026 Aasheesh Verma

This project is licensed under the MIT License. See [LICENSE](LICENSE) for the full text.