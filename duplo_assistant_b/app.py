import streamlit as st
from langchain.vectorstores.chroma import Chroma
from langchain.prompts import ChatPromptTemplate
from langchain_community.llms.ollama import Ollama
from get_embedding_function import get_embedding_function

from flask import Flask, request, jsonify
from flask_cors import CORS



app = Flask(__name__)
CORS(app)


CHROMA_PATH = "/db_chat"

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

    model = Ollama(model="llama3.2")
    response_text = model.invoke(prompt)
    # print(context_text)  # Printing before giving it to llm

    sources = [doc.metadata.get("id", None) for doc, _score in results]
    return response_text, sources


st.title("Interactive Chatbot")
st.write(
    "This Chatbot will answer your question based on the document availble locally"
)

query_text = st.text_input("Enter your query:", placeholder="Type your question here..")
if st.button("Submit") and query_text:
    with st.spinner("Generating responseee..."):
        try:
            response, sources = query_rag(query_text)
            st.success("response generated")
            st.write(f"**Response:**{response}")
            st.write(
                f"**Sources:**{', '.join(str(source) for source in sources if source)}"
            )

        except Exception as e:
            st.error(f"An error occured : {e}")


@app.route('/process', methods=['POST'])
def process():
    data = request.json
   
    query_text = data['text']
    if not query_text:
        return jsonify({'error': 'No query text provided'}), 400
    result,sources = query_rag(query_text)
    return jsonify({'result': result, "sources": sources})

if __name__ == '__main__':
    app.run(debug=True)

