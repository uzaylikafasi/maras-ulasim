# Çevrimdışı model şeması v1

Uygulama ile sunucu arasındaki ortak sözleşme. Üretici: `maras-ulasim-veri/model_uret.py` (sunucuda da aynı betik kullanılır). Şema numarası `1`; yalnız geriye uyumlu eklemeler (yeni isteğe bağlı alanlar) bu sürümde kalır, okuyucular bilinmeyen alanları yok sayar. Kırıcı değişiklik `schema: 2` olur; uygulama kendi desteklediğinden büyük şemayı indirmez.

## Dosyalar

| Dosya | İçerik |
|---|---|
| `offline-model.json.gz` | Model, UTF-8 JSON, gzip (`mtime=0`, aynı içerik aynı baytlar). Uygulamada tohum: `assets/offline-model.json.gz`. |
| `model-meta.json` | Modelin küçük özeti (aşağıda). Uygulamada tohumun özeti: `assets/offline-model-meta.json`. |

`sha256`, **gzip dosyasının baytlarının** SHA-256 değeridir (küçük harf onaltılık).

## Sunucu uçları (uygulamanın beklediği)

- `GET {sunucu}/v1/offline-model/meta` -> diğer uçlar gibi zarf: `{"data": <model-meta.json>, "meta": {...}}`. Uygulama zarfsız doğrudan `model-meta.json` gövdesini de kabul eder.
- `GET {sunucu}/offline-model` -> `offline-model.json.gz` baytları (`Content-Type: application/gzip`, 4 MB JSON sınırından muaf, ikili).
- Uygulama günde bir meta'yı çeker; `sha256` bildiğinden farklıysa ve `schema` desteklenenden büyük değilse dosyayı indirir, sha256 + şema + `model_version` eşleşmesini doğrular, atomik değiştirir. 404/ağ hatasında sessizce eski modeli korur.

## model-meta.json

```json
{
  "schema": 1,
  "model_version": "20261113-ba3d383f",
  "sha256": "<64 hex>",
  "size_bytes": 400198,
  "raw_size_bytes": 1740279,
  "file": "offline-model.json.gz",
  "built_at": "2026-11-13T02:00:00Z",
  "generator": "model_uret.py 1.0",
  "days": 4, "day_list": ["2026-11-10", "..."],
  "rounds": 195, "samples": 33866, "pairs": 23116,
  "first": "ISO8601", "last": "ISO8601",
  "coverage": {"WORKDAY": {"days": 4, "hours": [9, 10]}, "SATURDAY": {"days": 0, "hours": []}, "SUNDAY": {"days": 0, "hours": []}},
  "cells": {"observed": 1158, "sufficient": 811},
  "lines": {"total": 117, "with_estimates": 87}
}
```

Zorunlu alanlar: `schema`, `model_version`, `sha256`, `built_at`. `model_version` her içerik değişiminde değişir. `days`: gözlem yapılan takvim günü sayısı; `samples`: kullanılan araç gözlemi (satır) sayısı; `pairs`: öğrenmede kullanılan ardışık konum çifti sayısı.

## offline-model.json

```json
{
  "schema": 1, "model_version": "...", "built_at": "ISO8601 UTC", "tz": "Europe/Istanbul",
  "source": { "days": 4, "day_list": [], "rounds": 195, "samples": 0, "pairs": 0, "first": "", "last": "", "coverage": {}, "notes": "" },
  "stops": [ {"id": 4314, "code": "10170", "name": "ÇOCUK HASTANESİ", "lat": 37.59457, "lon": 36.88294} ],
  "timetables": { "H/1": [ {"routeCode": "H/1", "routeName": "...", "routeType": 1,
                            "lineSchedules": {"WORKDAY": ["06:20"], "SATURDAY": [], "SUNDAY": []}} ] },
  "lines": [ {
    "id": 9576, "dir": 1, "code": "H/1", "name": "OTOGAR - ... (Gidiş)", "dirName": "Gidiş",
    "stops": [6376, 6713],
    "shape": [[36.91644, 37.55049]],
    "route_type": 1, "route_type_basis": "direction",
    "est": { "cum": [0.0, 0.031, 1.0], "profile_n": 120, "profile_basis": "learned",
             "trip": { "WORKDAY": [null, {"sec": 1830, "n": 25}], "SATURDAY": null, "SUNDAY": null } }
  } ]
}
```

- `stops`: hatlarda geçen tüm duraklar (kaynak API'nin `id`, `code`, `name`, konum).
- `timetables`: `/v1/timetables?line_code=` yanıtındaki `timeTable` listesi, hat koduna göre, sözcük sözcük.
- `lines[]`: bir hat yönü (kaynak API'nin `id` + `direction` ikilisi). `name` kaynakta olduğu gibi yön ekiyle ("(Gidiş)"). `stops`: durak kimlikleri, `sequenceNo` sırasıyla. `shape`: GeoJSON sırasıyla `[boylam, enlem]` (yoksa alan yok).
- `route_type`: bu yönün tarifesi `timetables[code]` içinde `routeType` değeri bu olan kayıttır. `route_type_basis`: `direction` (routeType == yön; kanıtlı), `single` (tabloda tek kayıt var, varsayım), `none` (eşleşme yok, tarife gösterilmez).
- `est.cum`: her durağa kadar kümülatif sefer süresi payı; `cum[0]=0`, `cum[son]=1`, uzunluk = `stops` uzunluğu. `profile_basis`: `learned` (>=15 konum çifti) ya da `geometry` (durak arası mesafeye göre varsayım).
- `est.trip[gün tipi][saat]`: ilk duraktan son durağa sefer süresi. Gün tipi `WORKDAY` (Pzt-Cum), `SATURDAY`, `SUNDAY`; 24 elemanlı dizi (saat 0-23) ya da o gün tipi için hiç veri yoksa `null`. Eleman `null` = **yetersiz** (altında: <6 çift, <2 gün ya da yetersiz kapsama); uygulama tahmin üretmez, tarifeye düşer. Dolu eleman `{"sec": sefer süresi saniye, "n": çift sayısı}`.

## Tahmin kuralı (uygulama)

Gün tipine ait tarife kalkış saati `D` için durak `i` geçişi = `D + sec * cum[i]` (saniye). `sec` hücresi, tahmini geçiş saatinin saatine göre seçilir. Hücre yetersizse yalnız tarife kalkışı gösterilir. Araç konumu hiçbir koşulda üretilmez.

## Veri sınırları (tohum model)

Tohum, 10-13 Kasım 2026 hafta içi (Sal-Per, 09:00-24:00 civarı) toplamasından üretildi: cumartesi/pazar ve sabah 09:00 öncesi için hücre yok. Resmî tatiller ayrı işlenmez. Sunucu toplamaya başladıkça bu hücreler dolar.

## Ham kayıt biçimi (üretici girdisi)

Satır başına bir hat yönü, tur sırasıyla, UTF-8 `.jsonl` ya da `.jsonl.gz`:
`{"tur": "ISO tur başlangıcı", "t": "ISO API alma zamanı", "h": hatId, "k": "kod", "y": yön, "v": [{"p": plaka, "la": enlem, "lo": boylam, "s": sıradakiDurakId, "o": öncekiDurakId, "h": hız, "a": açı, "k": timeToNearestStop}]}`.
Statik klasör: `hatlar.json` (`{hatId: {kod, yon, ad}}`), `duraklar.json` (`{hatId: lineStops[]}`), `seferler.json` (`{kod: timeTable[]}`), `sekiller.json` (`{hatId: koordinat[]}`).

## routeType ↔ yön

`seferler.json` `routeType` ile hat yönü: 24 hatta (iki yönlü, iki tarifesi farklı) gözlemler `routeType == yön` varsayımına, ters varsayımdan daha iyi uydu; ters varsayımın daha iyi uyduğu hat 0, 10 hat belirsiz (tarifeler benzer). Kaynak `routeName` her iki tipte de aynı (ilk yönün adı) olduğundan ad kanıt değildir. Kanıtı yeniden üretmek: `model_uret.py dogrula`.
