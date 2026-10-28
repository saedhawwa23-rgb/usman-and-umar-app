FROM python:3.11-slim

ENV HOME=/home/user \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN useradd --create-home --uid 1000 user
WORKDIR /app

COPY --chown=user:user requirements.txt ./
RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r requirements.txt

COPY --chown=user:user main.py ./

EXPOSE 7860
USER user

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]
