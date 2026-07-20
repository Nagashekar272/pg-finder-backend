# Firestore Service Account Setup

## Why this is needed
The Firebase Admin SDK (used by the backend to read/write Cloud Firestore) requires a **service account key** to authenticate. Without it, the backend cannot connect to Firestore.

---

## Step 1: Download the Service Account JSON

1. Go to [Firebase Console](https://console.firebase.google.com/) → Select project **pg-find-c23ab**
2. Click the **⚙️ gear icon** → **Project Settings**
3. Go to the **Service Accounts** tab
4. Click **"Generate new private key"** → **"Generate Key"**
5. A JSON file (e.g. `pg-find-c23ab-firebase-adminsdk-xxxxx.json`) will download

---

## Step 2: Choose how to provide credentials

### Option A — Local Development (recommended)

Place the file somewhere safe (e.g. `d:\pg-find\pg-finder-backend\serviceaccount.json`) and set the environment variable:

```powershell
# Run this in PowerShell before starting the backend
$env:GOOGLE_APPLICATION_CREDENTIALS = "d:\pg-find\pg-finder-backend\serviceaccount.json"
```

Or add it to a `.env` file at `d:\pg-find\pg-finder-backend\.env`:
```
GOOGLE_APPLICATION_CREDENTIALS=d:\pg-find\pg-finder-backend\serviceaccount.json
```

> [!CAUTION]
> **NEVER commit the service account JSON file to Git.** It is already in `.gitignore` but double-check.

### Option B — Production / Render.com Deployment

Paste the **entire contents** of the JSON file as an environment variable in Render dashboard:

- Variable name: `FIREBASE_SERVICE_ACCOUNT_JSON`
- Value: the complete JSON string (paste as-is)

---

## Step 3: Verify `.gitignore` excludes secrets

Make sure these patterns are in `d:\pg-find\pg-finder-backend\.gitignore`:

```
serviceaccount.json
*-firebase-adminsdk-*.json
*.json
.env
```

---

## Step 4: Enable Firestore in Firebase Console

1. In [Firebase Console](https://console.firebase.google.com/) → **Firestore Database**
2. If not already created, click **"Create database"**
3. Choose **Production mode** (or Test mode for development)
4. Select region (e.g. **asia-south1** for India)

---

## Step 5: Set Firestore Security Rules (for backend-only access)

In Firebase Console → Firestore → **Rules**, use:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Backend Admin SDK bypasses these rules entirely
    // Deny all direct client access for security
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

The Admin SDK used by your Spring Boot backend **always bypasses Firestore security rules** — it has full access.
