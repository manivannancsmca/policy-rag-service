## Step-by-Step Setup & Execution Guide

### Step 1: Start Container Infrastructure
Run Docker Compose in detached mode:

```bash
docker compose up -d
```

### Step 2: Provision Models inside Ollama
Execute model pulls inside the running Ollama container:

```bash
# Pull Llama 3.2 Chat Model
docker exec -it rag-ollama ollama pull llama3.2

# Pull High Performance Embedding Model
docker exec -it rag-ollama ollama pull nomic-embed-text
```

Verify that models are loaded:

```bash
docker exec -it rag-ollama ollama list
```

*(Note: If you are running Ollama directly on your host machine without Docker, you can pull the model directly using `ollama pull nomic-embed-text`)*

### Step 3: Run the Spring Boot Application
Ensure JDK 25 is installed and active in your local shell environment:

```bash
java -version
mvn clean spring-boot:run
```

---

### Step 4: Testing the Application Endpoints

#### 1. Ingest Enterprise PDFs (Batch)
Place your PDF policy documents inside a directory (e.g., `/var/company_policies/`) and invoke the batch ingestion endpoint:

```bash
curl -X POST "http://localhost:8080/api/v1/admin/ingest?path=/var/company_policies"
```

**Expected Response:**
```json
{
  "totalFilesProcessed": 42,
  "totalChunksCreated": 4820,
  "durationMs": 34120,
  "status": "SUCCESS"
}
```

#### 2. Upload a Single Document
To upload a specific file from your local machine, use the `PUT` endpoint. Provide the full, absolute path to the file.

**For Windows (Command Prompt):**
Use `%USERPROFILE%` as a shortcut to your user directory.

```cmd
curl -X PUT http://localhost:8080/api/v1/admin/documents/update \
  -H "Content-Type: multipart/form-data" \
  -F "file=@%USERPROFILE%\Downloads\HR_Leave_Policy_2025.pdf"
```

#### 3. Ask a Document Question (Valid Policy)
```bash
curl -X POST http://localhost:8080/api/v1/policy/ask \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "emp-10294",
    "question": "What is the maximum carry-over limit for annual paid leave?"
  }'
```

**Expected Response:**
```json
{
  "conversationId": "emp-10294",
  "question": "What is the maximum carry-over limit for annual paid leave?",
  "answer": "Employees are allowed to carry over a maximum of 5 unused paid leave days into the next calendar year. Any additional unused leave beyond 5 days will lapse automatically on December 31st.",
  "informationFound": true,
  "citations": [
    {
      "sourceDocument": "HR_Leave_Policy_2025.pdf",
      "pageNumber": 12
    }
  ]
}
```

#### 4. Ask an Unrelated Query (Hallucination & Refusal Test)
```bash
curl -X POST http://localhost:8080/api/v1/policy/ask \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "emp-10294",
    "question": "How do I make a chocolate cake at home?"
  }'
```

**Expected Response:**
```json
{
  "conversationId": "emp-10294",
  "question": "How do I make a chocolate cake at home?",
  "answer": "The requested information is not available in the company policy documents.",
  "informationFound": false,
  "citations": []
}
```
