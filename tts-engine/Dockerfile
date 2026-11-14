FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libsndfile1 && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY requirements.txt requirements-voxcpm.txt ./
ARG WITH_VOXCPM=false
RUN pip install --no-cache-dir -r requirements.txt && \
    if [ "$WITH_VOXCPM" = "true" ]; then pip install --no-cache-dir -r requirements-voxcpm.txt; fi
COPY app ./app
ENV ENGINE_DATA_DIR=/data ENGINE_MODE=auto
EXPOSE 8808
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8808"]
