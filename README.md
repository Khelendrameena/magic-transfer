# ✨ Magic Transfer

Haath ke ishaare se photo ek phone se dusre phone me — bina pairing, bina Bluetooth,
bina cable. Dono phone bas ek hi web page kholte hain aur same 4-digit code dalte hain.

## Kaise kaam karta hai (sach)

"Bilkul bina connection" wala transfer physically impossible hai — data ko koi na koi
raasta chahiye. Is app ka raasta ek chhota server hai (jaise WhatsApp Web):

1. **Bhejne wala phone:** photo kholo → camera ke saamne **khula haath (open palm)
   1 second** rakho → photo "gayab" hoke server par chala jata hai.
2. **Lene wala phone:** **wahi khula haath** camera ke saamne rakho → photo server se
   utar ke screen par "hazir" ho jata hai.

User ko kabhi koi connection banana nahi padta — isiliye ye magic lagta hai.
Haath pehchanna phone ke browser me hi hota hai (MediaPipe Hands), photo sirf
gesture ke moment par server se guzarta hai aur 10 minute me auto-delete ho jata hai.

## Chalana (apne PC / WSL par)

```bash
pip install flask
python server.py
```

Phir dusre phones se kholne ke liye tunnel (Adda wala hi pattern):

```bash
cloudflared tunnel --url http://localhost:5000
```

Jo `https://…trycloudflare.com` link mile, use **dono phone** me kholo,
**same 4-digit code** dalo — ek par "Bhejna hai", dusre par "Lena hai".

Ya Render par `server.py` deploy kar do to permanent link mil jayega.

## Notes

- Camera ke liye page **https** par hona chahiye (trycloudflare link https hi deta hai).
- Gesture fail ho to "Gesture bina bhejo/lo" wala button bhi hai.
- Photo server ki memory me rehta hai, 10 min baad apne aap expire.
