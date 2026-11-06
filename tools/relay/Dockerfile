FROM python:3.12-alpine
WORKDIR /app
COPY relay.py /app/relay.py
ENV RELAY_PORT=27016
EXPOSE 27016/udp
CMD ["python", "-u", "/app/relay.py"]
