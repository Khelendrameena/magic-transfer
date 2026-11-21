# ✨ Magic Transfer — Haath hi ID hai

Photo kisi code ya pairing se nahi, **haath ki pehchan** se map hoti hai —
jaise fingerprint hota hai, waise hi haath is photo ka address ban jata hai.

## Flow

1. **Bhejne wala phone:** photo chuno → **khula haath camera ke saamne 1 second
   scan karo**. Browser haath ke 21 points (MediaPipe landmarks) se ek
   *hand signature* banata hai — ungliyo ki lambai ke ratios, jo haath ke size
   aur position se badalte nahi. Photo server par **usi signature ke saath
   map (bind)** ho jati hai.
2. **Lene wala phone:** **wahi haath scan karo** → signature server par bheja
   jata hai → sabse close matching mapping milti hai → **wahi photo** screen
   par aa jati hai.

Dusra haath scan karoge to uski photo aayegi (ya kuch nahi, agar us haath se
koi photo mapped hi nahi hai). Koi room code nahi, koi pairing nahi.

## Sach wali baat

- Haath ka signature **geometry** hai (ungli/haddi ke ratios), asli fingerprint
  jaisi biometric security nahi. Ye magic-trick ke liye perfect hai, bank
  locker ke liye nahi.
- Wahi haath, lagbhag waise hi khula rakho jaisa bhejte time rakha tha —
  match sabse pakka tabhi hota hai. Signature kai frames ka average hota hai,
  isliye chhota-mota fark chal jata hai.
- Photo server ki memory me rehti hai, **30 minute** me auto-expire.

## Chalana (apne PC / WSL par)

```bash
pip install flask
python server.py
cloudflared tunnel --url http://localhost:5000
```

Jo `https://…trycloudflare.com` link mile use dono phone me kholo —
ek par "Photo map karna hai", dusre par "Haath se photo lena hai".
(Camera ke liye https link zaroori hai, trycloudflare wahi deta hai.)
