# Maraş Ulaşım

Kahramanmaraş otobüsleri için Android uygulaması. Hatları haritada canlı izle, durağa gelen otobüsleri gör, favorilerini kaydet.

**[APK indir (1.0.0)](https://mithrixa.com/ulasim-api/download)** · Android 8.0 ve üzeri

> Bağımsız bir uygulamadır. Belediye veya Kahramankart'ın resmî uygulaması değildir. Veriler kaynağın verdiği gibidir; varış süreleri tahmindir.

## Neler var

- Hatları gidiş ve dönüşüyle haritada izleme (aynı anda 4 hat), yöne bakan otobüs işaretleri
- Durağa dokununca yaklaşan otobüsler; "Sadece favorilerim" ile yalnız favori hatların
- Yakınımdaki duraklar ve oradan geçen hatlar
- Favori hat, durak ve plaka; ana ekran widget'ı
- İki durak arası yolculuk önerisi, sefer saatleri, duyurular
- Okul/iş saatleri için hatırlatmalar
- Açık kaynak harita (MapLibre, OpenFreeMap, OpenStreetMap)

## Kurulum

APK'yı indirip aç. Android, Play Store dışından kurulum için "bilinmeyen kaynaklar" izni ister ve Play Protect uyarı gösterebilir; bu, mağaza dışı her uygulamada olur.

## Veri

Uygulama verileri [Maraş Ulaşım API](https://mithrixa.com/ulasim-api/docs)'sinden alır. API'nin kaynak kodu: [kahramankart-ulasim-api](https://github.com/uzaylikafasi/kahramankart-ulasim-api).

## Derleme

```sh
./gradlew :app:assembleDebug        # geliştirme sürümü
./gradlew :app:testDebugUnitTest    # birim testleri
```

Yayın imzası depoda yoktur. `~/.config/maras-ulasim-imza/signing.properties` (veya `MARAS_SIGNING` ortam değişkeni) varsa `assembleRelease` imzalı APK üretir. Raspberry Pi gibi ARM64 Linux makinelerde `./build-pi.sh` kullanılabilir.

Kotlin, Jetpack Compose, MapLibre Native, OkHttp, DataStore. Üçüncü taraf lisansları: [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

## Lisans

MIT
