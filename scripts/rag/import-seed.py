# -*- coding: utf-8 -*-
"""向时间银行 RAG 知识库导入真实业务规则资料（基于项目 README 与业务规则）。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8080"

DOCS = [
    {
        "title": "时间币是什么",
        "audience": 0,
        "type": "TEXT",
        "content": "时间币是「时光颐养」时间银行系统的社区互助记账积分，用于记录志愿服务时长。1 个时间币对应 1 分钟志愿服务时长。老人通过发布服务需求获得帮助，志愿者通过提供服务获得时间币。时间币为封闭记账积分，仅用于系统内记录与互助激励，不支持提现、兑换或投资。",
    },
    {
        "title": "老人如何发布活动",
        "audience": 1,
        "type": "FAQ",
        "content": "老人登录后可在老人端发布服务活动，填写活动标题、名额（1 到 10 人）、活动描述、每人答谢时间币（0 到 20）等信息。活动发布后需经管理员审核，审核通过后志愿者才能报名。老人可以随时查看活动报名情况，并在服务完成后确认答谢。",
    },
    {
        "title": "志愿者如何报名和签到",
        "audience": 2,
        "type": "FAQ",
        "content": "志愿者登录后可浏览和报名活动。报名后需在活动时间到现场签到，签到时系统会生成一次性签到凭证，凭证有时效限制，且须在活动时间窗内使用。只有完成现场签到的志愿者，才能在服务完成后获得时间币答谢。未签到的志愿者不能获得答谢。",
    },
    {
        "title": "时间币如何转账",
        "audience": 0,
        "type": "FAQ",
        "content": "用户登录后可在时间币页面向其他用户转账。转账需要输入登录密码进行验证，转账金额必须为正整数，且不能转账给自己。账户被冻结后将无法登录、无法发起转账等操作。转账结果以结算状态为准。",
    },
    {
        "title": "如何给志愿者答谢时间币",
        "audience": 1,
        "type": "FAQ",
        "content": "志愿者完成服务后，老人可以确认答谢。答谢前志愿者必须先完成现场签到，未签到的志愿者不能获得答谢。批量答谢会预检人数、总额和余额，超过上限时需要分批处理。答谢发放后以逐笔结算状态为准。",
    },
    {
        "title": "账户冻结与违约处理",
        "audience": 0,
        "type": "FAQ",
        "content": "平台管理员可以冻结违规账户，冻结后该账户立即停权，无法登录或发起任何操作。对于违约行为，平台支持追讨已发放的时间币。冻结与解冻操作需要管理员审核确认，属于高风险操作，不会由系统自动执行。",
    },
    {
        "title": "密码与账号安全说明",
        "audience": 0,
        "type": "FAQ",
        "content": "系统密码使用加盐哈希加密存储，密码长度至少 8 位，且需同时包含字母和数字。请勿使用简单密码。修改密码后，旧的登录凭证将立即失效。请妥善保管账号密码，不要泄露给他人。",
    },
]


def request(method, path, body=None, headers=None):
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    # 1. 管理员登录
    login = request("POST", "/login", {"username": "admin", "password": "123456", "role": 3})
    token = login["data"]["token"]
    print("登录成功，token 长度", len(token))

    # 2. 逐条导入
    for i, doc in enumerate(DOCS):
        try:
            r = request(
                "POST",
                "/administrator/knowledge",
                {"title": doc["title"], "content": doc["content"], "audience": doc["audience"], "type": doc["type"]},
                {"token": token, "Idempotency-Key": "rag-seed-" + str(i + 1)},
            )
            print(f"[{i+1}/{len(DOCS)}] 导入《{doc['title']}》: code={r.get('code')}")
        except Exception as e:
            print(f"[{i+1}/{len(DOCS)}] 导入《{doc['title']}》失败: {e}")


if __name__ == "__main__":
    main()
