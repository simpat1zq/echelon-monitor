FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# OpenCV's face detector (YuNet) and recognizer (SFace) for picking the runner.
RUN python -c "import os, urllib.request as u; os.makedirs('models'); \
    [u.urlretrieve('https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/' + p, \
                   'models/' + p.split('/')[1]) for p in \
     ('face_detection_yunet/face_detection_yunet_2023mar.onnx', \
      'face_recognition_sface/face_recognition_sface_2021dec.onnx')]"
COPY stride_monitor.py ./
# web/ also holds echelon-overlay.apk when the CI build downloaded it.
COPY web/ ./web/

ARG APP_VERSION=dev
ARG APP_BUILD_DATE=
ENV PYTHONUNBUFFERED=1 APP_VERSION=$APP_VERSION APP_BUILD_DATE=$APP_BUILD_DATE
EXPOSE 8080
ENTRYPOINT ["python", "stride_monitor.py"]
