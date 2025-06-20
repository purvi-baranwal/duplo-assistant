from langchain_ollama import OllamaEmbeddings, OllamaLLM


def get_embedding_function():
    embeddings = OllamaEmbeddings(model="mxbai-embed-large")

    return embeddings
