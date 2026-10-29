FROM python:3.11-slim

ENV HOME=/home/user \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN useradd --create-home --uid 1000 user
WORKDIR /app

COPY --chown=user:user requirements.txt ./
RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r requirements.txt

RUN mkdir -p /ms-playwright \
    && python -m playwright install --with-deps chromium \
    && chmod -R a+rX /ms-playwright

COPY --chown=user:user main.py browser_use_interactive.py ./

EXPOSE 7860
USER user

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]
