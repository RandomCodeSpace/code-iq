from fastapi import FastAPI, HTTPException
from typing import List

app = FastAPI()
router = APIRouter(prefix="/api/v1/users")


@router.get("/")
async def list_users():
    return await UserService.get_all()


@router.post("/")
async def create_user(user: UserCreate):
    return await UserService.create(user)


@router.get("/{user_id}")
async def get_user(user_id: int):
    user = await UserService.get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user


@router.delete("/{user_id}")
async def delete_user(user_id: int):
    await UserService.delete(user_id)
    return {"status": "deleted"}


@app.get("/health")
async def health_check():
    return {"status": "healthy"}
