🏥 E-HealthCare Management System
A complete virtual doctor consultation platform with hospital bed tracking, pharmacy management, online payments, and a full audit trail — built with pure Java and Spring Boot.

🔗 Live Demos:

Railway: https://ehms-production-f47b.up.railway.app
Render: https://ehms-9mvl.onrender.com
JavaSpring BootTestsRailwayRender

📋 Table of Contents
Live Demo
Overview
Features
Tech Stack
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
Live Demo
Railway (primary): https://ehms-production-f47b.up.railway.appRender (backup): https://ehms-9mvl.onrender.com

Register your own admin/doctor/patient/hospital accounts and test the full workflow.

⚠️ Note: On free tiers, data resets when services restart.

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
| **Database** | SQLite (default), MySQL, or file-based |
| **Connection Pool** | HikariCP (or built-in fallback) |
| **Logging** | SLF4J → Logback |
| **Payments** | Paystack (₦ native) + Stripe + Mock gateway |
| **PDF Generation** | Apache PDFBox (Unicode) + pure-Java writer (fallback) |
| **Testing** | JUnit 5 + JDK HttpClient + Playwright (UI, opt-in) |
| **Build** | Maven 3.8+ |
| **Deployment** | Railway + Render (Docker) |

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
mvn test
mvn spring-boot:run
# Open http://localhost:8000/
IntelliJ IDEA
File → Open → select the ehms folder
Wait for Maven import
Set Project SDK to JDK 17+
Run EhmsApplication (green ▶)
Open http://localhost:8000/
Running the Application
mvn spring-boot:run
With Flags
# Custom port
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

# SQLite database
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.db-url=sqlite:ehms.db"

# Paystack payments
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.paystack-key=sk_test_your_key"

# CAPTCHA disabled
mvn spring-boot:run -Dspring-boot.run.arguments="--ehms.captcha-difficulty=0"
| Flag | Default | Description |
|------|---------|-------------|
| `[port]` | `8000` | Server port |
| `--db <url>` | file | `sqlite:ehms.db` or `mysql://...` |
| `--admin-key <key>` | `ehms-admin-key` | Admin registration key |
| `--captcha <N>` | `3` | CAPTCHA difficulty (0 = off) |
| `--paystack-key <key>` | empty | Paystack secret key |
| `--backups <N>` | `10` | Backup files to keep |

Testing
mvn test              # All 64 tests
mvn test -Dtest=HospitalTests   # One suite
| Suite | Tests | What it covers |
|-------|-------|---------------|
| `UtilTests` | 11 | JSON, password hasher, multipart |
| `SecurityTests` | 9 | Sessions, login guard, rate limiter |
| `RegistrationTests` | 6 | All role registrations |
| `AppointmentTests` | 10 | Slots, booking, chat, payment |
| `HospitalTests` | 12 | Beds, wards, billing, pharmacy |
| `PdfAuditTests` | 4 | PDF generation, audit trail |
| `HttpIntegrationTests` | 9 | Full HTTP: routing, cookies, guards |
| `BootApiTests` | 3 | Spring Boot: Actuator, full flow |
| **Total** | **64** | **All green** ✅ |

Configuration
All settings in src/main/resources/application.yml:
ehms:
  db-url: ""                    # "" = file, "sqlite:ehms.db" = SQLite
  admin-key: ehms-admin-key
  captcha-difficulty: 3         # 0 = disabled
  paystack-key: ""            # sk_test_... or sk_live_...
  stripe-key: ""
  stripe-currency: ngn
  backups: 10
Payment Gateways
| Gateway | Use Case | Setup |
|---------|----------|-------|
| **Paystack** | Nigerian deployments (₦ native) | Set `ehms.paystack-key` |
| **Stripe** | International | Set `ehms.stripe-key` |
| **Mock** | Development | No key needed |

Test card: 4041 0408 8408 4081, PIN: 1234, OTP: 123456
Multi-Language & Currency
Languages (7)
| Code | Language | Currency |
|------|----------|----------|
| `en` | English | ₦ |
| `hi` | हिन्दी | ₹ |
| `bn` | বাংলা | ₹ |
| `pcm` | Naijá | ₦ |
| `yo` | Yorùbá | ₦ |
| `ha` | Hausa | ₦ |
| `ig` | Igbo | ₦ |

Currencies (30)
INR, NGN, USD, EUR, GBP, JPY, CNY, KRW, AUD, CAD, CHF, SGD, HKD, BRL, MXN, ZAR, GHS, KES, TZS, UGX, EGP, AED, SAR, PKR, BDT, LKR, PHP, THB, TRY, RUB

Security
| Feature | Implementation |
|---------|----------------|
| Password Hashing | PBKDF2-HMAC-SHA256, 120K iterations, random salt |
| Session Management | 256-bit tokens, HttpOnly cookies |
| Login CAPTCHA | Proof-of-work (SHA-256) |
| Brute-Force Protection | Account locked 5 min after 5 failures |
| Rate Limiting | 120 req/min/IP |
| HTTPS | Self-signed or Let's Encrypt |
| Anti-Enumeration | Identical error for wrong password/unknown email |

Database
| Mode | URL | Best for |
|------|-----|----------|
| **File** | `""` | Development |
| **SQLite** | `sqlite:ehms.db` | Small deployments |
| **MySQL** | `mysql://host:3306/ehms?user=...` | Production |

Reset:
rm -f ehms.dat ehms.db
mvn spring-boot:run
Deployment
Live Deployments
| Platform | URL | Cost |
|----------|-----|------|
| **Railway** | https://ehms-production-f47b.up.railway.app | Free ($5/30 days) |
| **Render** | https://ehms-9mvl.onrender.com | Free forever |

Deploy Your Own
# 1. Build locally
mvn clean package -DskipTests -B
mkdir -p deploy/classes deploy/lib
cp -r target/classes/* deploy/classes/
mvn dependency:copy-dependencies -DoutputDirectory=deploy/lib -DincludeScope=runtime -B

# 2. Push
git add -A && git add -f deploy/ && git add -f Dockerfile
git commit -m "Deploy"
git push origin main
Dockerfile:
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY deploy/classes/ classes/
COPY deploy/lib/ lib/
EXPOSE 8000
CMD ["sh", "-c", "java -Xmx300m -cp 'classes:lib/*' ehms.boot.EhmsApplication --server.port=$PORT --server.address=0.0.0.0"]
Project Structure
ehms/
├── Dockerfile
├── pom.xml
├── src/
│   ├── main/java/ehms/
│   │   ├── Main.java
│   │   ├── model/          # 19 entities
│   ├── main/resources/
│   │   ├── application.yml
│   │   └── webui.html      # Complete SPA
│   ├── optional/java/ehms/  # Paystack, Stripe, PDFBox
│   └── test/java/ehms/test/ # 10 suites, 64 tests
├── deploy/
│   ├── classes/            # Compiled bytecode
│   └── lib/               # Runtime JARs
├── README.md
└── .gitignore
API Endpoints
| Category | Endpoint | Description |
|----------|----------|-------------|
| **Auth** | `POST /api/login` | Sign in |
| | `POST /api/register/{role}` | Register |
| **Patient** | `POST /api/patient/book` | Book slot |
| | `POST /api/chat/send` | Chat message |
| | `GET /api/prescription/pdf` | Download PDF |
| | `POST /api/payment/pay` | Pay fee |
| **Doctor** | `POST /api/slots/publish` | Publish slots |
| | `POST /api/doctor/consult` | Complete consult |
| **Hospital** | `POST /api/hospital/beds` | Bed overview |
| | `POST /api/hospital/discharge` | Discharge + bill |
| **Admin** | `POST /api/admin/stats` | Global stats |
| **Monitor** | `GET /actuator/health` | Health check |
🤝 Contributing
Fork → 2. Branch → 3. Commit → 4. Push → 5. Pull Request
📄 License
MIT License

<div align="center">

Built with ❤️ and pure Java

⭐ Star this repository if you found it helpful!

</div>
```
