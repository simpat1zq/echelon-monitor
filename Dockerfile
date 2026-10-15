FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY stride_monitor.py index.html runs.html debug.html manifest.webmanifest sw.js icon-192.png icon-512.png favicon.ico apple-touch-icon.png ./

ARG APP_VERSION=dev
ARG APP_BUILD_DATE=
ENV PYTHONUNBUFFERED=1 APP_VERSION=$APP_VERSION APP_BUILD_DATE=$APP_BUILD_DATE
EXPOSE 8080
ENTRYPOINT ["python", "stride_monitor.py"]
