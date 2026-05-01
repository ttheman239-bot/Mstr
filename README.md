# BTC vs MSTR — Android App

แอป Android (Kotlin + Jetpack Compose) ที่แสดงกราฟเปรียบเทียบราคา Bitcoin (BTC) และหุ้น MicroStrategy (MSTR) แรงบันดาลใจจาก https://bitbo.io/stocks/mstr/

## ฟีเจอร์
- กราฟเส้นเปรียบเทียบราคา BTC vs MSTR (normalize เพื่อดูแนวโน้ม)
- กราฟ ratio BTC / MSTR
- เลือกช่วงเวลา 30D / 90D / 6M / 1Y / 2Y
- ดึงข้อมูลสดจาก CoinGecko (BTC) และ Yahoo Finance (MSTR)
- รีเฟรชข้อมูลด้วยปุ่ม

## ดาวน์โหลด APK
ดูไฟล์ `BTCvsMSTR-debug.apk` ใน [GitHub Releases](../../releases/latest)

## วิธีติดตั้ง
1. ดาวน์โหลด `BTCvsMSTR-debug.apk` จาก Releases
2. เปิดไฟล์บนมือถือ Android (อาจต้องอนุญาต "Install unknown apps")
3. กดติดตั้ง

## Build เอง
```bash
./gradlew :app:assembleDebug
```
ไฟล์ APK จะอยู่ที่ `app/build/outputs/apk/debug/app-debug.apk`
