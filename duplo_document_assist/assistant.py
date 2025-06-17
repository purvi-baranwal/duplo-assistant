import psycopg2
from langchain import LangChain

# Connect to your PostgreSQL database
conn = psycopg2.connect(
    dbname="mydatabase",
    user="myuser",
    password="mypassword",
    host="localhost",
    port="5432"
)
cursor = conn.cursor()

# Initialize LangChain
langchain = LangChain()

# Define a function to convert text to SQL and execute the query
def text_to_sql(text):
    # Use LangChain to generate SQL from text
    sql_query = langchain.text_to_sql(text)

    try:
        # Execute the SQL query
        cursor.execute(sql_query)
        # Fetch and return the results
        results = cursor.fetchall()
        return results
    except Exception as e:
        return str(e)

# Example prompt to convert text to SQL
prompt = "Show all employees with a salary greater than 50000"

# Convert the prompt to SQL and execute
results = text_to_sql(prompt)
print(results)

# Close the database connection
cursor.close()
conn.close()