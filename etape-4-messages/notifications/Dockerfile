FROM python:3.14.8-slim-trixie
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --root-user-action=ignore \
    -r requirements.txt
COPY notifications.py .
# Utilisateur sans privileges ; /donnees recoit le fichier SQLite des
# reservations deja confirmees
RUN useradd appli && mkdir /donnees && chown appli /donnees
USER appli
CMD ["python", "notifications.py"]
