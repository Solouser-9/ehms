🏥 E-HealthCare Management System
A complete virtual doctor consultation platform with hospital bed tracking, pharmacy management, online payments, and a full audit trail — built with pure Java and Spring Boot.

JavaSpring BootTestsLicense

📋 Table of Contents
Overview
Features
Tech Stack
Architecture
Quick Start
Running the Application
Testing
Configuration
Payment Gateways
Multi-Language & Currency
Security
Database
Deployment
Project Structure
API Endpoints
Contributing
Overview
At the time of the 2020's pandemic, everyone needed a virtual doctor who could attend to them via a smart device. This application keeps both the doctor and the patient socially distant and safe — consultations, prescriptions, payments, and hospital management all happen online.

The system supports four user roles (Patient, Doctor, Hospital, Admin) with a complete workflow:

Patient registers → Books a time slot → Chats with doctor → Uploads lab reports
↓
Doctor verifies → Publishes slots → Reviews symptoms → Issues prescription
↓
Patient pays consultation fee → Requests hospital bed → Hospital approves & admits
↓
Hospital manages beds → Attaches equipment → Dispenses medicine → Discharges with bill
↓
Patient pays hospital bill → Downloads prescription & bill PDFs

---

## Features

### 👤 Patient Portal
- Register with demographic details (age, blood group, address)
- Browse verified doctors with search, specialization filter, and fee filter
- Book consultations in specific time slots
- Real-time chat with the treating doctor
- Upload medical reports (images and PDFs)
- View and print prescriptions
- Download prescription and bill PDFs
- Pay consultation fees and hospital bills online
- Request hospital bed admission
- View personal audit trail

### 👨‍⚕️ Doctor Dashboard
- Register with medical licence verification workflow
- Publish bookable time slots
- Review patient symptoms and lab reports
- Chat with patients in real time
- Issue diagnosis and prescriptions
- Track earnings and outstanding payments
- Toggle availability

### 🏥 Hospital Panel
- Register with initial bed capacity
- Manage beds by ward with capacity enforcement
- Add ICU/Ventilator/General beds
- Approve or reject patient bed requests
- Attach equipment to occupied beds
- Manage pharmacy inventory with stock alerts
- Dispense medicine (stock tracked, price snapshot)
- Automatic consolidated billing on discharge (bed + pharmacy + equipment)
- Download bill PDFs

### 🔐 Admin Panel
- Verify doctor licences
- Block/unblock any account
- Global statistics dashboard
- Full audit trail viewer

### Cross-Cutting
- 🔒 PBKDF2 password hashing (120,000 iterations)
- 🍪 HttpOnly session cookies with SameSite=Strict
- 🛡️ Proof-of-work CAPTCHA on login
- ⏱️ Account lockout after 5 failed logins
- 🚦 Per-IP rate limiting (120 req/min)
- 📊 Live statistics dashboard with charts
- 📝 Complete audit trail (who, what, when)
- 💾 Automatic backups with upload archiving

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Java 17+ |
| **Framework** | Spring Boot 3.3.4 (web, WebSocket, Actuator) |
| **Web Server** | Apache Tomcat (embedded) + JDK HttpServer (dual-launcher) |
| **Database** | SQLite (default), MySQL, or file-based (Java serialization) |
| **Connection Pool** | HikariCP (or built-in fallback) |
| **Migrations** | Flyway |
| **Logging** | SLF4J → Logback (with runtime JUL fallback) |
| **Payments** | Paystack (₦ native) + Stripe + Mock gateway |
| **PDF Generation** | Apache PDFBox (Unicode) + pure-Java writer (fallback) |
| **Testing** | JUnit 5 + JDK HttpClient + Playwright (UI, opt-in) |
| **Build** | Maven 3.8+ |

---

## Architecture

The project uses a **dual-launcher architecture** — the same service layer runs behind two independent web servers:
┌──────────────────────────────────────────────────────────┐
│ Service Layer │
│ (Doctors, Patients, Beds, Billing, Pharmacy, Payments) │
└───────────────────────┬──────────────────────────────────┘
│
┌─────────────┴─────────────┐
│ │
┌──────▼──────┐ ┌──────▼──────┐
│ Classic │ │ Spring Boot │
│ Launcher │ │ Launcher │
│ (ehms.Main) │ │ (ehms.boot)│
│ │ │ │
│ JDK built-in │ │ Tomcat + │
│ HttpServer │ │ WebSocket │
│ ~66 routes │ │ ~60 routes │
└──────┬──────┘ └──────┬──────┘
│ │
┌──────▼──────────────────────────▼──────┐
│ Pluggable Store │
│ ┌─────────┐ ┌────────┐ ┌──────────┐ │
│ │FileStore│ │ SQLite │ │ MySQL │ │
│ │ehms.dat │ │ via │ │ via JDBC │ │
│ │ │ │ Hikari │ │ │ │
│ └─────────┘ └────────┘ └──────────┘ │
└────────────────────────────────────────┘

### Optional integrations (loaded reflectively):
src/optional/java/ehms/
├── pay/PaystackGateway.java ← Paystack Checkout API
├── pay/StripeGateway.java ← Stripe Checkout API
├── pdfbox/PdfBoxWriter.java ← Unicode PDF rendering
├── ws/WsServer.java ← Jetty WebSocket server
└── acme/AcmeProvisioner.java ← Let's Encrypt automation

The application **compiles and runs without any of these** — they're detected at runtime via `Class.forName()` and gracefully skipped if absent.

---

## Quick Start

### Prerequisites

| Requirement | Version | Check |
|-------------|---------|-------|
| JDK | 17 or newer | `java --version` |
| Maven | 3.8 or newer | `mvn --version` |

### Clone and Run

```bash
git clone https://github.com/Solouser-9/ehms.git
cd ehms

# Run the tests (first build downloads dependencies)
mvn test

# Start the server
mvn spring-boot:run

# Open in your browser
# http://localhost:8000/

IntelliJ IDEA
File → Open → select the ehms folder
Wait for Maven import (first time downloads ~200MB)
Set Project SDK to JDK 17+
Run EhmsApplication (green ▶ in src/main/java/ehms/boot/)
Open http://localhost:8000/

Running the Application
Spring Boot Launcher (recommended)
mvn spring-boot:run

Classic Launcher (pure Java, no Spring)
mvn package
java -cp "target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" ehms.Main

With Flags
# Custom port
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

# HTTPS with auto-generated certificate
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8443 --server.ssl.enabled=true"

# SQLite database with Flyway migrations
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.db-url=sqlite:ehms.db --spring.flyway.enabled=true --spring.flyway.url=jdbc:sqlite:ehms.db"

# Paystack payments enabled
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.paystack-key=sk_test_your_key_here"

# CAPTCHA disabled (for scripted testing)
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.captcha-difficulty=0"

CLI Flags Reference
| Flag | Default | Description |
|------|---------|-------------|
| `[port]` | `8000` | Server port |
| `--https` | off | Enable HTTPS (auto-generates self-signed cert) |
| `--db <url>` | file | `sqlite:ehms.db` or `mysql://host:3306/ehms?user=...` |
| `--admin-key <key>` | `ehms-admin-key` | Key required to register admin accounts |
| `--captcha <N>` | `3` | Proof-of-work difficulty (0 = disabled) |
| `--paystack-key <key>` | empty | Paystack secret key (sk_test_... or sk_live_...) |
| `--stripe-key <key>` | empty | Stripe secret key |
| `--backups <N>` | `10` | Number of backup files to keep |
| `--audit-cap <N>` | `2000` | Audit entries kept in memory |
| `--prorate` | off | Hourly billing proration |
| `--trust-proxy` | off | Trust X-Forwarded-For header |

Testing
# Run all 64 tests
mvn test

# Run a specific test suite
mvn test -Dtest=HospitalTests
mvn test -Dtest=HttpIntegrationTests
mvn test -Dtest=BootApiTests

# Run with debug logging
mvn test -Dehms.log.level=DEBUG

Test Coverage
| Suite | Tests | What it covers |
|-------|-------|---------------|
| `UtilTests` | 11 | JSON parser, password hasher, multipart parser |
| `SecurityTests` | 9 | Sessions, login guard, rate limiter, auth service |
| `RegistrationTests` | 6 | All role registrations, admin verification |
| `AppointmentTests` | 10 | Slot publishing, booking, chat, consult, payment |
| `HospitalTests` | 12 | Beds, wards, billing, bed requests, equipment, pharmacy |
| `PdfAuditTests` | 4 | PDF generation, audit trail |
| `HttpIntegrationTests` | 9 | Full HTTP: routing, cookies, guards, CAPTCHA, uploads |
| `BootApiTests` | 3 | Spring Boot layer: Actuator, full flow, error shapes |
| **Total** | **64** | **All green** ✅ |

Browser UI Tests (opt-in)
# One-time: download Chromium
mvn test-compile exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"

# Run UI tests
mvn test -Dtest=UiSmokeTests -Dsurefire.excludedGroups=

Configuration
All settings live in src/main/resources/application.yml:
ehms:
  db-url: ""                    # "" = file store, "sqlite:ehms.db" = SQLite
  admin-key: ehms-admin-key     # Required to register admin accounts
  captcha-difficulty: 3         # 0 = disabled (for scripts/CI)
  paystack-key: ""              # sk_test_... or sk_live_...
  stripe-key: ""                # Stripe secret key
  stripe-currency: ngn          # Charge currency (NGN default)
  backups: 10                   # Number of auto-backups to keep

Environment Variables
Spring Boot's relaxed binding maps these:
| YAML | Environment Variable |
|------|---------------------|
| `ehms.paystack-key` | `EHMS_PAYSTACKKEY` |
| `ehms.admin-key` | `EHMS_ADMINKEY` |
| `ehms.db-url` | `EHMS_DBURL` |
| `ehms.captcha-difficulty` | `EHMS_CAPTCHADIFFICULTY` |

Payment Gateways
Paystack (default for Nigerian deployments)
ehms:
  paystack-key: "sk_test_your_test_key_here"
  stripe-currency: ngn

Native Naira (₦) support
Redirect-based checkout flow
Server-side payment verification (forging return URLs does nothing)
Test card: 4084 0840 8408 4081, PIN: 1234, OTP: 123456
Get test keys at dashboard.paystack.com

Stripe
ehms:
  stripe-key: "sk_test_your_stripe_key"
  stripe-currency: usd    # or inr, eur, etc.

Mock Gateway (default)
If no key is set, payments settle instantly — perfect for development and testing.

Multi-Language & Currency
Supported Languages (7)
| Code | Language | Default Currency |
|------|----------|-----------------|
| `en` | English | ₦ (NGN) |
| `hi` | हिन्दी (Hindi) | ₹ (INR) |
| `bn` | বাংলা (Bengali) | ₹ (INR) |
| `pcm` | Naijá (Nigerian Pidgin) | ₦ (NGN) |
| `yo` | Yorùbá | ₦ (NGN) |
| `ha` | Hausa | ₦ (NGN) |
| `ig` | Igbo | ₦ (NGN) |

Supported Currencies (30)
The currency selector in the header allows independent currency choice:
INR, NGN, USD, EUR, GBP, JPY, CNY, KRW, AUD, CAD, CHF, SGD, HKD, BRL, MXN, ZAR, GHS, KES, TZS, UGX, EGP, AED, SAR, PKR, BDT, LKR, PHP, THB, TRY, RUB

"Auto" mode follows the language's default currency.

| Feature | Implementation |
|---------|----------------|
| **Password Hashing** | PBKDF2-HMAC-SHA256, 120,000 iterations, per-account random salt |
| **Session Management** | 256-bit tokens, HttpOnly + SameSite=Strict cookies, 8-hour expiry |
| **Login CAPTCHA** | Proof-of-work (SHA-256, difficulty 3 = ~4096 hashes) |
| **Brute-Force Protection** | Account locked 5 min after 5 failures; IP locked after 15 |
| **Rate Limiting** | 120 requests/minute/IP → HTTP 429 |
| **HTTPS** | Self-signed auto-generation or Let's Encrypt via ACME |
| **Anti-Enumeration** | Wrong password and unknown email give identical error messages |
| **Server-Side Verification** | Payment callbacks verified with secret key (never trust client) |
| **Timing Equalization** | Non-existent accounts burn the same PBKDF2 time as real ones |

Database
Three persistence modes:
| Mode | URL | Best for |
|------|-----|----------|
| **File** (default) | `""` (empty) | Development, single-user, zero-config |
| **SQLite** | `sqlite:ehms.db` | Small deployments, embedded, no server needed |
| **MySQL** | `mysql://host:3306/ehms?user=root&password=...` | Production, multi-user |

Features:
Automatic table creation and column migration (ALTER TABLE)
Dirty-table sync (only changed tables are written)
Connection pooling (HikariCP when available)
Timestamped backups with automatic pruning
Patient file uploads included in backups
Audit log append-only in SQL mode (full history preserved)
Reset the database:
# Stop the server, then:
rm -f ehms.dat ehms.db ehms.db-shm ehms.db-wal
rm -rf uploads/ backups/

# Restart — everything is empty
mvn spring-boot:run

Deployment
Build the fat JAR
mvn package
# Produces target/ehms.jar (thin) and target/ehms-boot.jar (fat/executable)

# Run the fat JAR
java -jar target/ehms-boot.jar

Deploy to Render (free tier)
Push to GitHub
Go to render.com → New → Web Service
Connect your repository
Build command: mvn clean package -DskipTests
Start command: java -jar target/ehms-boot.jar
Add environment variables:
EHMS_ADMINKEY: your admin key
EHMS_PAYSTACKKEY: your Paystack secret key
SPRING_DATASOURCE_URL: your database URL (if using MySQL)

Deploy with Docker
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src/ src/
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/ehms-boot.jar .
EXPOSE 8000
CMD ["java", "-jar", "ehms-boot.jar"]

docker build -t ehms .
docker run -p 8000:8000 -e EHMS_ADMINKEY=my-admin-key ehms

Build a standalone executable (no Java needed on target):
bash scripts/package.sh        # → dist/EHMS (bundled JRE)

Project Structure
ehms/
├── pom.xml                          # Maven build configuration
├── src/
│   ├── main/
│   │   ├── java/ehms/
│   │   │   ├── Main.java            # Classic launcher
│   │   │   ├── model/               # 19 entity classes
│   │   │   ├── db/                  # 6 classes: Database, stores, pool, backups
│   │   │   ├── security/            # 8 classes: hashing, sessions, CAPTCHA, rate limiting
│   │   │   ├── service/             # 20 classes: all business logic
│   │   │   ├── util/                # 6 classes: JSON, PDF, logging, multipart
│   │   │   ├── web/                 # 4 classes: WebServer, WebUi, PwaAssets, Config
│   │   │   ├── ws/                  # 1 class: WebSocketProvider interface
│   │   │   └── boot/                # 15 classes: Spring Boot layer
│   │   └── resources/
│   │       ├── application.yml      # Spring Boot configuration
│   │       ├── logback.xml          # Logging configuration
│   │       ├── webui.html           # The complete single-page UI
│   │       └── db/migration/        # Flyway SQL migrations
│   ├── optional/java/ehms/          # Optional integrations (reflective loading)
│   │   ├── pay/                     # PaystackGateway, StripeGateway
│   │   ├── pdfbox/                  # Unicode PDF writer
│   │   ├── ws/                      # Jetty WebSocket server
│   │   └── acme/                    # Let's Encrypt automation
│   └── test/java/ehms/test/        # 10 test suites, 64 tests
├── scripts/                          # Packaging scripts
│   ├── package.sh
│   ├── package.bat
│   └── package-boot.sh
├── README.md
└── .gitignore

API Endpoints
All endpoints use POST (JSON body) unless noted as GET.
Authentication
| Endpoint | Description |
|----------|-------------|
| `POST /api/login` | Sign in (with CAPTCHA proof) |
| `POST /api/logout` | Sign out |
| `POST /api/me` | Current session info |
| `POST /api/captcha` | Get proof-of-work challenge |
| `POST /api/register/{role}` | Register (patient/doctor/hospital/admin) |

Patient
| Endpoint | Description |
|----------|-------------|
| `POST /api/patient/book` | Book a consultation slot |
| `POST /api/patient/cancel` | Cancel a pending consultation |
| `POST /api/patient/appointments` | List my consultations |
| `POST /api/chat/send` | Send chat message |
| `POST /api/chat/messages` | Get chat thread |
| `POST /api/report/upload` | Upload medical report (multipart) |
| `POST /api/payment/pay` | Pay consultation fee |
| `POST /api/bed/request` | Request hospital bed |
| `POST /api/bill/pay` | Pay hospital bill |
| `GET /api/prescription/pdf` | Download prescription PDF |
| `GET /api/history/pdf` | Download all prescriptions PDF |
| `GET /api/bill/pdf` | Download bill PDF |

Doctor
| Endpoint | Description |
|----------|-------------|
| `POST /api/slots/publish` | Publish time slots |
| `POST /api/slots/mine` | List my slots |
| `POST /api/doctor/consult` | Complete consultation (diagnosis + prescription) |
| `POST /api/doctor/availability` | Toggle availability |
| `POST /api/payments/doctor` | View earnings |
| `POST /api/reports/patient` | View patient's reports |

Hospital
| Endpoint | Description |
|----------|-------------|
| `POST /api/hospital/beds` | Bed overview |
| `POST /api/hospital/beds/add` | Add beds |
| `POST /api/hospital/admit` | Admit patient directly |
| `POST /api/hospital/discharge` | Discharge (auto-generates consolidated bill) |
| `POST /api/hospital/prices/set` | Set bed prices |
| `POST /api/hospital/ward/save` | Create/update ward |
| `POST /api/equipment/assign` | Attach equipment to bed |
| `POST /api/pharmacy/add` | Add medicine |
| `POST /api/pharmacy/dispense` | Dispense medicine |
| `POST /api/bed/request/decide` | Approve/reject bed request |

Admin
| Endpoint | Description |
|----------|-------------|
| `POST /api/admin/stats` | Global statistics |
| `POST /api/admin/verify` | Verify doctor licence |
| `POST /api/admin/block` | Block/unblock account |
| `POST /api/admin/audit` | View audit trail |

Monitoring
| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Health check |
| `GET /actuator/prometheus` | Prometheus metrics |

🤝 Contributing
Fork the repository
Create a feature branch: git checkout -b feature/amazing-feature
Commit changes: git commit -m 'Add amazing feature'
Push: git push origin feature/amazing-feature
Open a Pull Request

📄 License
This project is licensed under the MIT License.

🙏 Acknowledgments
Spring Boot — the application framework
Paystack — Nigerian payment processing
Apache PDFBox — PDF generation
Flyway — database migrations
JUnit 5 — testing framework

📞 Support
If you encounter any issues or have questions:

Check the Issues page
Create a new issue with:
Your Java version (java --version)
Your Maven version (mvn --version)
The error message or screenshot
Steps to reproduce

<div align="center">

Built with ❤️ and pure Java

⭐ Star this repository if you found it helpful!

</div>
```
