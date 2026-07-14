<h4 align="right">English | <strong><a href="README.md">简体中文</a></strong></h4>

<div align="center">
  <img src=".github/assets/dctimer-logo.png" alt="DCTimer-BLE logo" width="128" height="128" />

  <h1>DCTimer-BLE</h1>

  <p>
    A speedcubing timer based on DCTimer-Android, with support for smart cubes and the QiYi Smart Timer
  </p>

  <p>
    <img alt="Android" src="https://img.shields.io/badge/Android-targetSdk%2035-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
    <img alt="Java" src="https://img.shields.io/badge/Java-17-007396?style=for-the-badge&logo=openjdk&logoColor=white" />
    <img alt="Gradle" src="https://img.shields.io/badge/Gradle-8.11.1-02303A?style=for-the-badge&logo=gradle&logoColor=white" />
  </p>

  <p>
    <img src="website/assets/web1.svg" alt="DCTimer-BLE timer screen" height="280" />
    <img src="website/assets/web3.svg" alt="DCTimer-BLE feature improvements" height="280" />
  </p>
</div>

---

## Download

- [Official download](https://dctimer.huizhi.ink)
- [GitHub Releases](https://github.com/huizhiLLL/DCTimer-Android-BLE/releases/latest)

## Notes

- DCTimer-BLE uses a new package name, so it will not conflict with the original DCTimer during installation
- DCTimer-BLE is compatible with the original data format. You can export data from the original DCTimer and import it into DCTimer-BLE
- On some devices, DCTimer may fail to export data. If that happens, remove `DCTimer` from the export path and keep `/storage/emulated/0/database.db`; when importing, choose that db file from the root path of phone storage

## Features

- Compatible with mainstream smart cube brands
- Supports CFOP/Roux solve reconstruction and dedicated training modes
  - CFOP: F2L/OLL/PLL/Last Layer/CLL/ELL/COLL/EOCP/2GLL/OLLCP/ZZLL/ZBLS/ZBLL
  - Roux: CMLL/LSE/L10P
- Draggable real-time 3D smart cube rendering and gyroscope-following view (currently MoYu only)
- Carefully optimized smart scramble guidance and correction flow
- Fast connection, with no manual MAC address entry required. From app launch to connected, it usually takes only 2-4 seconds

## Support

- `Moyu32` (MoYu smart cube)
- `QYSC` / `Tornado V4` (QiYi smart cube and Tornado series)
- `GAN` (`v2 / v3 / v4`) (GAN smart cube)
- `QiYi Smart Timer` (QiYi smart timer)

## New / Improved

- Manual time entry now auto-splits the time, so no extra decimal point is needed
- Added 8s/12s voice reminders for WCA inspection mode
- PB history markers and sorting in the solve list
- Maple Leaf scramble and FTO scramble state rendering
- Clock scramble state rendering adapted to the new WCA rules
- Database import/export, scramble import/export, and background image selection have been migrated to the system document picker
- Upgraded to `AndroidX / AGP 8.9.2 / Gradle 8.11.1 / targetSdk 35`

## Acknowledgements

- [DCTimer-Android](https://github.com/MeigenChou/DCTimer-Android): original DCTimer-Android repository
- [cstimer](https://github.com/cs0x7f/cstimer): smart cube protocol and partial algorithm reference
- [smartcube-web-bluetooth](https://github.com/poliva/smartcube-web-bluetooth): smart cube protocol reference
- [qiyi_smartcube_protocol](https://codeberg.org/Flying-Toast/qiyi_smartcube_protocol): smart cube protocol reference
- [CubicTimer](https://github.com/hato-ya/CubicTimer): QiYi Smart Timer protocol reference
- [Miaoyan](https://miaoyan.app): official website design reference
- [Codex](https://github.com/codex): development partner

- [Soda](https://space.bilibili.com/400839068): provided QiYi and Tornado smart cube test hardware
- [Visionary](https://space.bilibili.com/674586122): GAN smart cube testing
---

If this project is helpful to you, I hope you can give it a Star. It will motivate me to keep maintaining it ~

**Thanks to the following sponsors for supporting this project:**
- 9247
- [锤子](https://space.bilibili.com/3493132083661706)
- [贾梦](https://github.com/nmb1337)

<details>
  <summary>Sponsor support</summary>

  <p>
    <img src=".github/assets/sponsor-wechat.png" alt="WeChat Pay sponsor QR code" width="260" />
    <img src=".github/assets/sponsor-alipay.jpg" alt="Alipay sponsor QR code" width="260" />
  </p>
</details>

## License

GPLv3
