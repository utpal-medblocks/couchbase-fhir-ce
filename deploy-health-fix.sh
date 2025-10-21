#!/bin/bash
# Quick deployment script for health controller fixes

echo "🔨 Building backend with health controller fixes..."
cd backend
mvn clean compile -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful"
echo ""
echo "🐳 Rebuilding and restarting fhir-server..."
cd ..
docker-compose build fhir-server
docker-compose up -d fhir-server

echo ""
echo "⏳ Waiting 10 seconds for server to start..."
sleep 10

echo ""
echo "🧪 Testing health endpoint..."
curl -s http://localhost:8080/health/readiness | jq

echo ""
echo "🔍 Testing through HAProxy..."
curl -s http://localhost/fhir/test/Patient?_count=1

echo ""
echo "✅ Deployment complete!"
echo ""
echo "Check HAProxy stats: http://localhost/haproxy?stats (admin/admin)"

