"""Pure presentation policy. No networking, storage, permissions or model calls."""
from __future__ import annotations

SEMANTIC_TAGS = ("neutral", "happy", "sad", "angry", "surprised", "thinking", "shy")
MARKER = "[Live2D mobile call presentation v1]"


def allowed_tags(value) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    # The rendering adapter owns the actual model mapping. Never accept paths,
    # indexes, expression filenames or arbitrary prompt text as control tags.
    return tuple(tag for tag in SEMANTIC_TAGS if tag in value)


def build_presentation(config) -> str:
    text = (
        MARKER + "\n这是实时语音通话。沿用已经选定的人格、说话习惯和当前关系。"
        "先回应对方刚说的话，普通闲聊通常一至三句；对方要细讲时再展开。"
        "通话时用自然短句，每句只说一件事。普通闲聊先说重点，通常一到三句。把过长的句子用句号或问号拆开，少用一串逗号。用户要求朗诵、引用或按原文读时，保留原文。"
        "用措辞和句子的节奏表达感情。高兴时轻快一点，安慰时放柔，意外时先短短回应。用自然停顿、问句或适量感叹号体现语气，不加“轻声地”“笑着说”等旁白。"
        "不把分析、工具过程或Markdown结构读给对方。"
        "很短或没听清的片段就简短确认，不拿字数少、打字省来讽刺对方。"
        "手机面部线索只作本轮语气参考，不断定心情。不虚构看见了对方，不用舞台说明冒充动作。"
    )
    tags = allowed_tags(config.get("expression_tags", []))
    if config.get("expressions_enabled", True) and tags:
        text += (
            "\n每次完整回复最多在第一句话开头放一个表情控制标记："
            + "、".join("[" + tag + "]" for tag in tags)
            + "。标记只给界面使用，不朗读、不解释。只在情绪合适时选用；"
            + ("没有适合情绪时用[neutral]回到默认表情。" if "neutral" in tags else "没有合适情绪时不放标记。")
            + "也可在台词开头选一个静默动作：[gesture:nod]表示赞同，[gesture:shake]表示否定，"
              "[gesture:bow]表示感谢或道歉，[gesture:sway]表示俏皮，[gesture:raise-hand]表示招呼，"
              "[gesture:lower-hand]表示放下手。没有合适的动作就省略。其他文件名、数字或动作命令不要输出。"
              "双语JSON用约定的emotion和gesture字段，台词字段不要放标记。"
        )
    else:
        text += "\n本次不输出表情标记、表情文件名、数字或动作命令。"
    label = config.get("character_label", "")
    if isinstance(label, str) and label.strip() and len(label) <= 32 and not any(x in label for x in "\n\r[]<>"):
        text += "\n通话界面的角色标签为“" + label.strip() + "”；它不覆盖已选人格设定。"
    return text


def scope_matches(platform: str, sender: str, profile_id: str, config) -> bool:
    if config.get("enabled", True) is not True or platform != "webchat":
        return False
    owners = config.get("allowed_api_principals", [])
    profiles = config.get("allowed_profile_ids", [])
    return (
        isinstance(owners, list) and isinstance(profiles, list)
        and sender.startswith("api_key:") and sender in owners
        and bool(profile_id) and profile_id != "default" and profile_id in profiles
    )
