import request from './request.js'

export function sendVerificationCode(phone) {
  return request({
    method: 'post',
    url: '/user/code',
    params: { phone },
  })
}

export function login(credentials) {
  return request({
    method: 'post',
    url: '/user/login',
    data: credentials,
  })
}

export function fetchMe() {
  return request({
    method: 'get',
    url: '/user/me',
  })
}

export function logout() {
  return request({
    method: 'post',
    url: '/user/logout',
  })
}
