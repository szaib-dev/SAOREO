"""FCM wakes a device. Authenticated pairing endpoints supply the actual call state."""
import asyncio
import logging
import os

import aiohttp


class FcmSender:
    def __init__(self):
        self.credentials = None
        self.project = os.environ.get('TOGETHER_FCM_PROJECT', '')
        path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS')
        if path:
            from google.oauth2.service_account import Credentials
            self.credentials = Credentials.from_service_account_file(
                path, scopes=['https://www.googleapis.com/auth/firebase.messaging'])
            self.project = self.project or self.credentials.project_id
        self.lock = asyncio.Lock()

    async def send(self, token, identifier, ttl):
        if self.credentials is None or ttl <= 0:
            return False
        try:
            async with self.lock:
                if not self.credentials.valid:
                    from google.auth.transport.requests import Request
                    await asyncio.to_thread(self.credentials.refresh, Request())
                access = self.credentials.token
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as http:
                async with http.post(f'https://fcm.googleapis.com/v1/projects/{self.project}/messages:send',
                        headers={'Authorization': f'Bearer {access}'}, json={'message': {
                            'token': token, 'data': {'type': 'chat_wake'} if identifier is None else {'type': 'call_wake', 'ring': identifier},
                            'android': {'priority': 'HIGH', 'ttl': f'{max(1, min(ttl, 86400 if identifier is None else 30))}s',
                                        'restricted_package_name': 'com.codetic.saoreo'}}}) as response:
                    if response.status == 200:
                        return True
                    logging.warning('FCM wake rejected with HTTP %s', response.status)
        except Exception as error:
            logging.warning('FCM wake failed: %s', type(error).__name__)
        return False
