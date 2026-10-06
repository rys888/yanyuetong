import request from './request.js'

export function sendVerificationCode(phone) {
  return request({
    method: 'post',
    url: '/user/code',
    params: { phone },
  })
}
