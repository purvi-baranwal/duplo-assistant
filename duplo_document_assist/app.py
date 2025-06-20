from langchain_chroma import Chroma
from langchain.prompts import ChatPromptTemplate
from langchain_ollama import OllamaEmbeddings, OllamaLLM
from get_embedding_function import get_embedding_function
import os
import streamlit as st
from intent import get_best_intent
import requests

from flask import Flask, request, jsonify, render_template
from flask_cors import CORS

app = Flask(__name__)
CORS(app)



current_path = os.getcwd()
CHROMA_PATH = current_path + "/db_chat"
FRONT_END_PATH = current_path + "/templates/template.html"

PROMPT_TEMPLATE = """
Answer the question based only on the following context:

{context}

___
Answer the question based on the above context: {question}
"""


def query_rag(query_text: str):
    embedding_function = get_embedding_function()
    db = Chroma(persist_directory=CHROMA_PATH, embedding_function=embedding_function)
    results = db.similarity_search_with_score(query_text, k=4)

    # Combhine the results to pass llm

    context_text = "\n\n\n".join([doc.page_content for doc, _score in results])
    prompt_template = ChatPromptTemplate.from_template(PROMPT_TEMPLATE)
    prompt = prompt_template.format(context=context_text, question=query_text)

    model = OllamaLLM(model="llama3.2")
    response_text = model.invoke(prompt)
    sources = [doc.metadata.get("id", None) for doc, _score in results]
    return response_text, sources


@app.route('/')
def home():
    return render_template('template.html')

@app.route('/process', methods=['POST'])
def process():
    data = request.json
    query_text = data['text']
    conversationId = data['conversationID']
    if not query_text:
        return jsonify({'error': 'No query text provided'}), 400
    intent = get_best_intent(query_text)
    if intent =="answer":
        result,sources = query_rag(query_text)
        data = jsonify({'result': result, "sources": sources})
        return data
    if intent == "sql":
        url = "http://localhost:8080/assistant?conversationId=" + conversationId
        headers = {"Content-Type": "text/plain"}
        response = requests.post(url, headers=headers, data=query_text)
        return response







if __name__ == '__main__':
    app.run(debug=True)

