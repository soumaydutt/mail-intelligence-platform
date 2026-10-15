# MailGuard
![Java](https://img.shields.io/badge/Java-23-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green?style=for-the-badge&logo=springboot)
![Maven](https://img.shields.io/badge/Maven-Build-red?style=for-the-badge&logo=apachemaven)
![MySQL](https://img.shields.io/badge/MySQL-8-blue?style=for-the-badge&logo=mysql)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-green?style=for-the-badge&logo=swagger)

A lightweight Java library for validating email addresses using format validation, domain validation, MX record lookup, and SMTP verification.

This repository preserves the history and attribution of [the original MailGuard](https://github.com/aasheesh06/MailGuard). The email intelligence architecture in [PLAN.md](PLAN.md) is planned, not implemented; the current application remains the upstream single-address API.

---
## ✨ Features
- ✔ Email format validation
- ✔ Domain validation
- ✔ MX record lookup using DNS
- ✔ SMTP mailbox verification
- ✔ REST API built with Spring Boot
- ✔ Swagger/OpenAPI documentation
- ✔ MySQL validation history
- ✔ Detailed logging using SLF4J
- ✔ Layered architecture
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
MailGuard validates an email address through multiple verification stages.

Validation flow:

1. Email Format Validation
2. Domain Validation
3. MX Record Lookup
4. SMTP Verification

Every validation request is stored in the database for future reference.

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

Response

```json
{
  "email": "john@example.com",
  "domain": "example.com",
  "formatValid": true,
  "mxRecordFound": true,
  "smtpVerified": true,
  "status": "VALID",
  "message": "Email format and domain are valid."
}
```

## 🌐 MX Record Lookup
MailGuard automatically retrieves MX (Mail Exchange) records using DNS lookup.

Example:

```text
gmail.com
    ↓
MX Record
    ↓
gmail-smtp-in.l.google.com
```

Only domains having valid MX records proceed to SMTP verification.

## 📨 SMTP Verification
MailGuard establishes an SMTP connection with the recipient mail server to verify whether the email address exists.

SMTP Flow

```text
Client
   │
   ├── HELO
   ├── MAIL FROM
   ├── RCPT TO
   └── QUIT
        │
        ▼
Mail Server
```

If the server accepts the `RCPT TO` command, the email is considered deliverable.

## 📚 REST API
### Base URL

```
http://localhost:8080
```

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/email/validate` | Validate an email address |

## 📂 Project Structure
```text
src
├── controller
├── service
├── smtp
├── dns
├── validator
├── model
├── repository
├── dto
└── config
```

## 🛠 Technologies
- Java 23
- Spring Boot 4
- Spring Data JPA
- MySQL
- Maven
- DNSJava
- SMTP
- Swagger (OpenAPI)
- Lombok

## 📄 License
MIT License

Copyright (c) 2026 Aasheesh Verma

This project is licensed under the MIT License. See [LICENSE](LICENSE) for the full text.