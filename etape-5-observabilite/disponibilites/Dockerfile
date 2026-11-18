FROM python:3.14.8-slim-trixie
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --root-user-action=ignore \
    -r requirements.txt
COPY app/ .
# Utilisateur sans privileges ; /donnees recoit le fichier SQLite
RUN useradd --create-home appli && mkdir /donnees \
    && chown appli /donnees
USER appli
EXPOSE 8000
# --no-access-log : le journal JSON du middleware remplace celui d'Uvicorn
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", \
     "--no-access-log"]
