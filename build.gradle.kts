buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP's built-in Kotlin must include the CVE-2026-53914 fix.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        // Keep AGP's crypto modules aligned, including the 1.85 certificate
        // name-constraints and ASN.1 nesting-depth fixes.
        classpath(platform("org.bouncycastle:bc-jdk18on-bom:1.85"))
        constraints {
            add("classpath", "org.apache.commons:commons-lang3:3.18.0") {
                because("CVE-2025-48924: uncontrolled recursion")
            }
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6") {
                because("CVE-2024-29371: compressed JWE denial of service")
            }
            add("classpath", "org.jdom:jdom2:2.0.6.1") {
                because("CVE-2021-33813: external XML entity injection")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}

