def extract_notifications(file_path):
    notifications = {'app': {}, 'email': {}}
    ignore_keys = ['buttonIntro', 'linkIntro', 'buttonLabel', 'footer', 'manageSettings', 'html']
    with open(file_path, 'r', encoding='utf-8') as file:
        for line in file:
            line = line.strip()
            if '=' in line:
                key, value = line.split('=', 1)
                key_parts = key.split('.')
                if 'notification' in key_parts[0]:  # Ensure it starts with 'notification'
                    if not any(ignore in line for ignore in ignore_keys):
                        if 'app' in key_parts or 'email' in key_parts:
                            notification_type = 'app' if 'app' in key_parts else 'email'
                            
                            if 'email' in key_parts and 'body' in key_parts:
                                field_type = 'body'
                                notification_key = '.'.join(key_parts[:key_parts.index('email')])  # Base notification key
                            elif 'body' in key_parts[-2]:
                                field_type = 'body'
                                notification_key = '.'.join(key_parts[:-3])
                            else:
                                field_type = key_parts[-1]
                                notification_key = '.'.join(key_parts[:-2])

                            if notification_key not in notifications[notification_type]:
                                notifications[notification_type][notification_key] = {'title': None, 'body': '', 'subject': None}
                            
                            if field_type == 'body' and notifications[notification_type][notification_key]['body']:
                                notifications[notification_type][notification_key]['body'] += " " + value.strip()
                            elif field_type == 'body':
                                notifications[notification_type][notification_key]['body'] = value.strip()
                            else:
                                notifications[notification_type][notification_key][field_type] = value.strip()
    return notifications

def generate_html(notifications, output_file_path):
    html_content = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
    <meta charset="UTF-8">
    <title>Notification Details</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        table { width: 100%; border-collapse: collapse; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #f2f2f2; }
        h2 { margin-top: 40px; }
    </style>
    </head>
    <body>
    <h1>In-App Notification Details</h1>
    <table>
        <thead>
            <tr>
                <th>#</th>
                <th>Notification Key</th>
                <th>Title</th>
                <th>Body</th>
            </tr>
        </thead>
        <tbody>
    """
    row_number = 1
    for key, details in notifications['app'].items():
        html_content += f"<tr><td>{row_number}</td><td>{key}</td><td>{details['title']}</td><td>{details['body']}</td></tr>"
        row_number += 1
    html_content += """
        </tbody>
    </table>
    <h2>Email Notification Details</h2>
    <table>
        <thead>
            <tr>
                <th>#</th>
                <th>Notification Key</th>
                <th>Title</th>
                <th>Body</th>
                <th>Subject</th>
            </tr>
        </thead>
        <tbody>
    """
    row_number = 1
    for key, details in notifications['email'].items():
        html_content += f"<tr><td>{row_number}</td><td>{key}</td><td>{details['title']}</td><td>{details['body']}</td><td>{details['subject']}</td></tr>"
        row_number += 1
    html_content += """
        </tbody>
    </table>
    </body>
    </html>
    """

    with open(output_file_path, 'w', encoding='utf-8') as file:
        file.write(html_content)

def main():
    file_path = 'src/main/resources/i18n/Messages_en.properties'  
    output_file_path = 'docs/notifications.html'
    notifications = extract_notifications(file_path)
    generate_html(notifications, output_file_path)
    print("HTML file has been generated with notification details.")

if __name__ == "__main__":
    main()
