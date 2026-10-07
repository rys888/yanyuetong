const DLQ_REPLAY_REASON_MESSAGES = {
  REPLAY_CONFIRM_NACK: '消息队列未确认（NACK），台账未做任何变更',
  REPLAY_CONFIRM_RETURN: '消息被交换机退回，台账未做任何变更',
  REPLAY_PUBLISH_UNKNOWN: '投递结果不确定，台账未做任何变更，请刷新确认',
}

export function describeDlqReplayReason(reason) {
  if (reason == null || reason === '') return { text: '重放失败', code: null }
  const originalReason = String(reason)
  const code = Object.keys(DLQ_REPLAY_REASON_MESSAGES).find((item) => originalReason.includes(item))
  if (code) {
    return { text: DLQ_REPLAY_REASON_MESSAGES[code], code }
  }
  return { text: reason, code: null }
}
