// Front-camera helpers for face recognition: open the camera into a <video>,
// grab JPEG frames, close it again. The server turns frames into face
// signatures; photos aren't kept anywhere.
window.FaceCam = {
  // Needs a secure page (https or localhost) and a browser/app with camera access.
  supported() {
    return !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  },

  async open(video) {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } }, audio: false,
    });
    video.muted = true;
    video.playsInline = true;
    video.srcObject = stream;
    await video.play();
    return stream;
  },

  close(stream) {
    if (stream) stream.getTracks().forEach((t) => t.stop());
  },

  snap(video) {
    const c = document.createElement("canvas");
    c.width = video.videoWidth || 640;
    c.height = video.videoHeight || 480;
    c.getContext("2d").drawImage(video, 0, 0, c.width, c.height);
    return new Promise((resolve) => c.toBlob(resolve, "image/jpeg", 0.85));
  },

  async post(url, blob) {
    const r = await fetch(url, { method: "POST", headers: { "Content-Type": "image/jpeg" }, body: blob });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
  },

  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
};
