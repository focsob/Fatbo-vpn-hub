#!/usr/bin/env python3
"""
搶 Oracle Cloud「Always Free」ARM 機（VM.Standard.A1.Flex，最多 4 OCPU / 24 GB）。

熱門地區（例如 Singapore）成日 "Out of host capacity"。呢個 script 會喺你所有
availability domain 之間不停重試，搶到一部就停、印出 public IP，然後收工。

佢只係用 OCI API 喺【你自己個 Oracle 戶口】開一部【你本身免費額度內】嘅機。
憑證放喺 ~/.oci/config，唔會寫落呢個檔，亦唔好畀任何人。

────────────────────── 一次性設定 ──────────────────────
1) 喺 Oracle 控制台（新戶口）：右上角頭像 → 「API keys」→「Add API key」
   →「Generate API key pair」→ 下載私鑰 → Add。把佢顯示嘅 config 區塊
   貼入 ~/.oci/config，私鑰放喺 config 指住嗰個路徑。
   （或者： pip install oci-cli ；然後 oci setup config 跟住做。）
2) pip install oci
3) 確保有一個 VCN + public subnet：控制台 → Networking → Virtual Cloud Networks
   →「Start VCN Wizard」→「VCN with Internet Connectivity」（1 分鐘）。
   （或者喺控制台試開一次 instance，佢會自動整個預設 VCN，等佢 capacity fail
     都冇所謂 —— 呢個 bot 會攞返嗰個 subnet 用。）
4) 冇 SSH key 就先： ssh-keygen -t ed25519   然後填返下面公鑰路徑。
5) python3 oci-grab-free-arm.py     （擺喺度跑，Ctrl-C 停。建議用 tmux/screen。）

提示：4 OCPU/24 GB 最難搶。真係搶唔到，就把 OCPUS/MEM_GB 調細（例如 1 / 6）——
做 Cloudflare Tunnel / VPN 出口其實乜都唔使，1 OCPU 綽綽有餘。
────────────────────────────────────────────────────────
"""
import os
import sys
import time
import random
import datetime

try:
    import oci
except ImportError:
    sys.exit("請先安裝： pip install oci")

# ───────────── 設定（通常淨係改 SSH 公鑰路徑就得） ─────────────
SSH_PUBLIC_KEY_PATH = os.environ.get("OCI_SSH_PUB", "~/.ssh/id_ed25519.pub")
OCPUS = int(os.environ.get("OCI_OCPUS", "4"))    # A1 免費上限 = 4 OCPU（搶唔到可調細，如 1）
MEM_GB = int(os.environ.get("OCI_MEM_GB", "24"))  # A1 免費上限 = 24 GB（連 OCPUS 一齊調細，如 6）
DISPLAY_NAME = os.environ.get("OCI_NAME", "fatbo-exit")
OS_NAME = "Canonical Ubuntu"
OS_VER = os.environ.get("OCI_OS_VER", "26.04")    # 或 "24.04"
BOOT_GB = 50
INTERVAL = int(os.environ.get("OCI_INTERVAL", "90"))  # 每一圈重試之間停幾多秒（唔好太密，免被 rate limit）
COMPARTMENT_ID = ""  # 留空 = 用 ~/.oci/config 嘅 tenancy（root compartment）
SUBNET_ID = ""       # 留空 = 自動揀 compartment 入面第一個 subnet
IMAGE_ID = ""        # 留空 = 自動揀最新嘅 Ubuntu aarch64（啱 A1）
SHAPE = "VM.Standard.A1.Flex"
# ────────────────────────────────────────────────────────────


def log(*a):
    print(datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def main():
    config = oci.config.from_file()
    compartment = COMPARTMENT_ID or config["tenancy"]
    identity = oci.identity.IdentityClient(config)
    compute = oci.core.ComputeClient(config)
    network = oci.core.VirtualNetworkClient(config)

    pub = open(os.path.expanduser(SSH_PUBLIC_KEY_PATH)).read().strip()

    ads = [ad.name for ad in identity.list_availability_domains(compartment).data]
    log("region:", config.get("region"), "| availability domains:", ads)

    image_id = IMAGE_ID
    if not image_id:
        imgs = compute.list_images(
            compartment, operating_system=OS_NAME, operating_system_version=OS_VER,
            shape=SHAPE, sort_by="TIMECREATED", sort_order="DESC",
        ).data
        if not imgs:
            sys.exit(f"搵唔到 {OS_NAME} {OS_VER} 嘅 aarch64 image，試吓改 OS_VER=\"24.04\"")
        image_id = imgs[0].id
        log("image:", imgs[0].display_name)

    subnet_id = SUBNET_ID
    if not subnet_id:
        subnets = network.list_subnets(compartment).data
        if not subnets:
            sys.exit("搵唔到 subnet。先喺控制台 Networking → VCN Wizard →"
                     " 'VCN with Internet Connectivity' 整一個，再行過。")
        subnet_id = subnets[0].id
        log("subnet:", subnets[0].display_name)

    shape_config = oci.core.models.LaunchInstanceShapeConfigDetails(
        ocpus=OCPUS, memory_in_gbs=MEM_GB)

    log(f"開始搶 {SHAPE}  {OCPUS} OCPU / {MEM_GB} GB … (Ctrl-C 停)")
    rnd = 0
    while True:
        rnd += 1
        for ad in ads:
            details = oci.core.models.LaunchInstanceDetails(
                compartment_id=compartment,
                availability_domain=ad,
                display_name=DISPLAY_NAME,
                shape=SHAPE,
                shape_config=shape_config,
                source_details=oci.core.models.InstanceSourceViaImageDetails(
                    image_id=image_id, boot_volume_size_in_gbs=BOOT_GB),
                create_vnic_details=oci.core.models.CreateVnicDetails(
                    subnet_id=subnet_id, assign_public_ip=True),
                metadata={"ssh_authorized_keys": pub},
            )
            try:
                inst = compute.launch_instance(details).data
                log("🎉 搶到！instance 建立緊：", inst.id, "@", ad)
                wait_for_ip(compute, network, inst)
                return
            except oci.exceptions.ServiceError as e:
                msg = (e.message or "").lower()
                code = (e.code or "")
                if e.status == 500 or "capacity" in msg:
                    log(f"[第 {rnd} 圈] {ad}: 冇容量 (out of capacity)")
                elif e.status == 429 or code == "TooManyRequests":
                    log(f"[第 {rnd} 圈] {ad}: 被 rate limit，多停 60 秒")
                    time.sleep(60)
                elif "limitexceeded" in code.lower() or "limit" in msg:
                    sys.exit("超出免費額度：你可能已經有一部 A1，或者 OCPUS/MEM 大過"
                             " 免費上限 (4/24)。調細 OCPUS/MEM_GB。詳情：" + (e.message or ""))
                elif e.status in (401, 404) or code in ("NotAuthenticated", "NotAuthorizedOrNotFound"):
                    sys.exit(f"認證／權限出錯（{e.status} {code}）：檢查 ~/.oci/config、"
                             f"API key、同 compartment/subnet OCID。詳情：{e.message}")
                else:
                    log(f"{ad}: 錯誤 {e.status} {code}: {e.message}")
        time.sleep(INTERVAL + random.uniform(0, 20))


def wait_for_ip(compute, network, inst):
    log("等緊 RUNNING + public IP …")
    for _ in range(60):
        time.sleep(10)
        cur = compute.get_instance(inst.id).data
        if cur.lifecycle_state == "RUNNING":
            for va in compute.list_vnic_attachments(
                    inst.compartment_id, instance_id=inst.id).data:
                v = network.get_vnic(va.vnic_id).data
                if v.public_ip:
                    log("✅ RUNNING。Public IP：", v.public_ip)
                    log("   連機： ssh ubuntu@" + v.public_ip)
                    return
        elif cur.lifecycle_state in ("TERMINATED", "TERMINATING"):
            log("instance 變咗", cur.lifecycle_state, "，請檢查控制台")
            return
    log("instance id：", inst.id, "（IP 未攞到，入控制台睇）")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log("已停止。")
