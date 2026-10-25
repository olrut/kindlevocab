import { useState } from 'react';

/**
 * Plays a recording fetched through the API (the token has to be attached, so no plain
 * <audio src>), or the browser's speech synthesis when there is none or it fails.
 */
export function AudioButton({ word, load }: { word: string; load: (() => Promise<Blob>) | null }) {
  const [busy, setBusy] = useState(false);

  async function play() {
    setBusy(true);
    try {
      if (load) {
        try {
          const url = URL.createObjectURL(await load());
          const audio = new Audio(url);
          await audio.play();
          setTimeout(() => URL.revokeObjectURL(url), 60_000);
          return;
        } catch {
          // Fall through to speech synthesis.
        }
      }
      speak(word);
    } finally {
      setBusy(false);
    }
  }

  return (
    <button type="button" onClick={play} disabled={busy} aria-label="Play pronunciation" title="Play pronunciation">
      ▶
    </button>
  );
}

function speak(word: string) {
  if (!('speechSynthesis' in window)) return;
  const utterance = new SpeechSynthesisUtterance(word);
  utterance.lang = 'en-US';
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(utterance);
}
