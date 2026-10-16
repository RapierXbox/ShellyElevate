FROM python:3.11-slim
RUN pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu \
    torch==2.13.0 torchaudio==2.11.0 litert-torch==0.9.4 ai-edge-litert==2.2.0 scipy
COPY convert.py /convert.py
ENTRYPOINT ["python", "/convert.py"]
