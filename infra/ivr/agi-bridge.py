#!/usr/bin/env python3
"""Pont Asterisk (AGI) → plateforme VAS (/callbacks/ivr). Une seule configuration Asterisk pour tous les menus IVR :

    ; extensions.conf : numéros surtaxés routés vers le pont
    exten => _80XXXXXX,1,Answer()
     same => n,AGI(agi-bridge.py)
     same => n,Hangup()

Variables d'environnement d'Asterisk (fichier /etc/default/asterisk ou systemd) : VAS_IVR_URL (ex. https://vas.interne:8080/callbacks/ivr),
VAS_CALLBACK_SECRET (= CALLBACK_SECRET du .env), VAS_TTS (commande de synthèse vocale, défaut : espeak-ng), VAS_TTS_DIR (défaut :
/var/lib/asterisk/sounds/vas-tts). Un texte « sound:nom » joue le fichier son Asterisk « nom » (enregistrements studio) au lieu de la synthèse.
Le raccroché est toujours signalé (durée mesurée par le pont) : la facturation à la minute est faite par la plateforme.
"""
import hashlib, json, os, subprocess, sys, time, urllib.parse, urllib.request

URL = os.environ.get("VAS_IVR_URL", "http://127.0.0.1:8080/callbacks/ivr")
SECRET = os.environ.get("VAS_CALLBACK_SECRET", "")
TTS = os.environ.get("VAS_TTS", "espeak-ng")
TTS_DIR = os.environ.get("VAS_TTS_DIR", "/var/lib/asterisk/sounds/vas-tts")
VOICES = {"fr": "fr", "ar": "ar", "en": "en"}


def agi_env():
    env = {}
    for line in sys.stdin:
        line = line.strip()
        if not line:
            break
        k, _, v = line.partition(":")
        env[k.strip()] = v.strip()
    return env


def agi(cmd):
    sys.stdout.write(cmd + "\n")
    sys.stdout.flush()
    res = sys.stdin.readline().strip()   # « 200 result=<x> ... »
    if not res.startswith("200"):
        raise EOFError(res)
    part = res.split("result=", 1)[1] if "result=" in res else ""
    return part.split(" ", 1)[0]


def call(params):
    data = urllib.parse.urlencode({**params, "secret": SECRET}).encode()
    with urllib.request.urlopen(urllib.request.Request(URL, data=data, method="POST"), timeout=8) as r:
        return json.loads(r.read().decode())


def sound(text, lang):
    """Fichier son à jouer : « sound:nom » tel quel, sinon synthèse vocale mise en cache (nom = empreinte du texte)."""
    if text.startswith("sound:"):
        return text[6:]
    os.makedirs(TTS_DIR, exist_ok=True)
    name = hashlib.sha256((lang + ":" + text).encode()).hexdigest()[:24]
    wav = os.path.join(TTS_DIR, name + ".wav")
    if not os.path.exists(wav):
        subprocess.run([TTS, "-v", VOICES.get(lang, "fr"), "-w", wav, text], check=True, timeout=20)
        subprocess.run(["sox", wav, "-r", "8000", "-c", "1", wav + ".8k.wav"], check=False, timeout=20)
        if os.path.exists(wav + ".8k.wav"):
            os.replace(wav + ".8k.wav", wav)
    return os.path.join(os.path.basename(TTS_DIR), name)


def main():
    env = agi_env()
    call_id = env.get("agi_uniqueid", str(time.time()))
    msisdn, number = env.get("agi_callerid", ""), env.get("agi_extension", "")
    started = time.time()
    try:
        r = call({"callId": call_id, "msisdn": msisdn, "number": number, "event": "start"})
        silent = 0
        while True:
            text, lang = r.get("text", ""), r.get("lang", "fr")
            if r.get("action") != "prompt":
                if text:
                    agi('STREAM FILE %s ""' % sound(text, lang))
                break
            digits = agi("GET DATA %s 8000 %d" % (sound(text, lang), max(1, int(r.get("maxDigits", 1)))))
            if digits in ("", "-1", "timeout"):
                digits = ""
                silent += 1
                if silent >= 3:   # trois silences : fin d'appel (l'abonné ne paie pas une attente sans fin)
                    break
            else:
                silent = 0
            r = call({"callId": call_id, "msisdn": msisdn, "event": "input", "digits": digits})
    except EOFError:
        pass   # l'abonné a raccroché pendant le menu
    finally:
        try:
            call({"callId": call_id, "msisdn": msisdn, "event": "hangup", "duration": int(time.time() - started)})
        except Exception:
            pass


if __name__ == "__main__":
    main()
