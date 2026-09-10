"""LoRA fine-tune a small model on the beat-rewriting task, then merge it.

    python train.py --base Qwen/Qwen2.5-0.5B-Instruct --epochs 3

The student learns one narrow mapping: a short fragment plus its surrounding
message goes in, three phrasings come out as JSON. It is not being taught
English, it already knows that. It is being taught a format and a discipline,
which is the kind of thing a few thousand examples buys.

The 0.5B is the interesting target. Off the shelf it scored 30% clean on the
fifty-message set, and it kept 100% of hard facts only because the pipeline's
guarantee reverts anything lossy. Its real weakness is distinctness, at 36%:
it produces three alternatives that are barely different from each other, which
is safe and useless. Distinctness is a learned behaviour, so it is exactly what
training should move.

At 397MB it is the only size that makes the download story comfortable.
"""

import argparse
import json
from pathlib import Path

import torch
from datasets import load_dataset
from peft import LoraConfig, PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer
from trl import SFTConfig, SFTTrainer

HERE = Path(__file__).parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-0.5B-Instruct")
    ap.add_argument("--data", default=str(HERE / "data" / "train.jsonl"))
    ap.add_argument("--out", default=str(HERE / "runs" / "beat-0.5b"))
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--accum", type=int, default=2)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--rank", type=int, default=16)
    args = ap.parse_args()

    out = Path(args.out)
    adapter_dir = out / "adapter"
    merged_dir = out / "merged"
    out.mkdir(parents=True, exist_ok=True)

    dataset = load_dataset("json", data_files={"train": args.data})["train"]
    print(f"{len(dataset)} training examples")
    print("example assistant turn:")
    print("  " + dataset[0]["messages"][-1]["content"][:200])

    lora = LoraConfig(
        r=args.rank,
        lora_alpha=args.rank * 2,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        # Every linear layer. On a model this small there is no reason to be shy,
        # and attention-only adapters underfit format-following tasks.
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
    )

    config = SFTConfig(
        output_dir=str(out / "checkpoints"),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch,
        gradient_accumulation_steps=args.accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=10,
        save_strategy="no",
        bf16=True,
        max_length=1024,
        # The prompt is nearly identical on every example, so training on it
        # teaches nothing and drowns the signal from the part that varies.
        completion_only_loss=True,
        report_to=[],
        seed=13,
    )

    trainer = SFTTrainer(
        model=args.base,
        args=config,
        train_dataset=dataset,
        peft_config=lora,
    )
    trainer.train()
    trainer.save_model(str(adapter_dir))
    print(f"adapter -> {adapter_dir}")

    # Merge, because an adapter cannot be quantised and shipped on its own.
    print("merging into the base weights")
    del trainer
    torch.cuda.empty_cache()

    base = AutoModelForCausalLM.from_pretrained(
        args.base, torch_dtype=torch.bfloat16, device_map="cpu",
    )
    merged = PeftModel.from_pretrained(base, str(adapter_dir)).merge_and_unload()
    merged.save_pretrained(str(merged_dir))
    AutoTokenizer.from_pretrained(args.base).save_pretrained(str(merged_dir))

    (out / "run.json").write_text(json.dumps({
        "base": args.base, "examples": len(dataset), "epochs": args.epochs,
        "lr": args.lr, "rank": args.rank,
        "batch": args.batch, "accum": args.accum,
    }, indent=2))
    print(f"merged -> {merged_dir}")


if __name__ == "__main__":
    main()
